// GPL-3.0. JNI bridge; pointers are owned and serialized by Java transports.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/bytestring.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <openssl/rsa.h>
#include "AdbCrypto.h"
#define JNI(name) Java_cn_ohmyterm_scrcpy_adb_NativeCrypto_##name
#define TLS(h) ((sc_tls *)(intptr_t)(h))
#define PAIR(h) ((sc_pair *)(intptr_t)(h))
static void fail(JNIEnv *e, const char *message) { jclass c=(*e)->FindClass(e,"java/io/IOException"); if(c)(*e)->ThrowNew(e,c,message); }
static jbyteArray bytes(JNIEnv *e, const uint8_t *p, int n) { jbyteArray b=(*e)->NewByteArray(e,n); if(b && n)(*e)->SetByteArrayRegion(e,b,0,n,(const jbyte *)p); return b; }
JNIEXPORT jlong JNICALL JNI(tlsCreate)(JNIEnv *e,jclass cls,jbyteArray der) {
    (void)cls; int n=(*e)->GetArrayLength(e,der); jbyte *p=(*e)->GetByteArrayElements(e,der,NULL); if(!p)return 0;
    CBS cbs; CBS_init(&cbs,(uint8_t *)p,n); EVP_PKEY *key=EVP_parse_private_key(&cbs);
    RSA *rsa=key?EVP_PKEY_get1_RSA(key):NULL; uint8_t *pkcs1=NULL;
    int count=rsa?i2d_RSAPrivateKey(rsa,&pkcs1):-1;
    sc_tls *t=count>0 && CBS_len(&cbs)==0?sc_tls_create(pkcs1,count):NULL;
    if(pkcs1) { OPENSSL_cleanse(pkcs1,count); OPENSSL_free(pkcs1); }
    RSA_free(rsa); EVP_PKEY_free(key); (*e)->ReleaseByteArrayElements(e,der,p,JNI_ABORT);
    if(!t)fail(e,"Cannot create ADB TLS identity"); return (jlong)(intptr_t)t;
}
JNIEXPORT void JNICALL JNI(tlsFree)(JNIEnv *e,jclass c,jlong h) { (void)e;(void)c;sc_tls_free(TLS(h)); }
JNIEXPORT jint JNICALL JNI(tlsHandshake)(JNIEnv *e,jclass c,jlong h) { (void)e;(void)c;return sc_tls_handshake(TLS(h)); }
JNIEXPORT jint JNICALL JNI(tlsIO)(JNIEnv *e,jclass c,jlong h,jbyteArray data,jint offset,jint length,jboolean write) {
    (void)c; int capacity=(*e)->GetArrayLength(e,data);
    if(offset<0 || length<0 || offset>capacity-length){fail(e,"Invalid TLS buffer");return -1;}
    jbyte *p=(*e)->GetByteArrayElements(e,data,NULL); if(!p)return -1;
    int r=write?sc_tls_write(TLS(h),(uint8_t *)p+offset,length):sc_tls_read(TLS(h),(uint8_t *)p+offset,length);
    (*e)->ReleaseByteArrayElements(e,data,p,write?JNI_ABORT:0);return r;
}
JNIEXPORT void JNICALL JNI(tlsFeed)(JNIEnv *e,jclass c,jlong h,jbyteArray data,jint count) {
    (void)c;if(count<0 || count>(*e)->GetArrayLength(e,data)){fail(e,"Invalid TLS input");return;}
    jbyte *p=(*e)->GetByteArrayElements(e,data,NULL);if(!p)return;
    int ok=sc_tls_feed(TLS(h),(uint8_t *)p,count);(*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);if(!ok)fail(e,"Cannot feed TLS input");
}
JNIEXPORT jbyteArray JNICALL JNI(tlsDrain)(JNIEnv *e,jclass c,jlong h) { (void)c;uint8_t buffer[32768];int n=sc_tls_drain(TLS(h),buffer,sizeof(buffer));return bytes(e,buffer,n); }
JNIEXPORT jbyteArray JNICALL JNI(tlsExport)(JNIEnv *e,jclass c,jlong h) { (void)c;uint8_t out[64];if(!sc_tls_export(TLS(h),out)){fail(e,"Cannot export TLS pairing key");return NULL;}return bytes(e,out,64); }
JNIEXPORT jlong JNICALL JNI(pairCreate)(JNIEnv *e,jclass c,jbyteArray secret) {
    (void)c;int n=(*e)->GetArrayLength(e,secret);jbyte *p=(*e)->GetByteArrayElements(e,secret,NULL);if(!p)return 0;
    sc_pair *pair=sc_pair_create((uint8_t *)p,n);(*e)->ReleaseByteArrayElements(e,secret,p,JNI_ABORT);if(!pair)fail(e,"Cannot create ADB pairing challenge");return (jlong)(intptr_t)pair;
}
JNIEXPORT void JNICALL JNI(pairFree)(JNIEnv *e,jclass c,jlong h) { (void)e;(void)c;sc_pair_free(PAIR(h)); }
JNIEXPORT jbyteArray JNICALL JNI(pairMessage)(JNIEnv *e,jclass c,jlong h) { (void)c;uint8_t out[128];int n=sc_pair_message(PAIR(h),out,sizeof(out));if(n<=0){fail(e,"Cannot create pairing message");return NULL;}return bytes(e,out,n); }
JNIEXPORT void JNICALL JNI(pairProcess)(JNIEnv *e,jclass c,jlong h,jbyteArray data) {
    (void)c;int n=(*e)->GetArrayLength(e,data);jbyte *p=(*e)->GetByteArrayElements(e,data,NULL);if(!p)return;
    int ok=sc_pair_process(PAIR(h),(uint8_t *)p,n);(*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);if(!ok)fail(e,"Pairing code rejected");
}
JNIEXPORT jbyteArray JNICALL JNI(pairCrypt)(JNIEnv *e,jclass c,jlong h,jbyteArray data,jboolean encrypt) {
    (void)c;int n=(*e)->GetArrayLength(e,data);if(n>8208){fail(e,"Oversized pairing identity");return NULL;}
    uint8_t out[8224];jbyte *p=(*e)->GetByteArrayElements(e,data,NULL);if(!p)return NULL;
    int count=encrypt?sc_pair_encrypt(PAIR(h),(uint8_t *)p,n,out,sizeof(out)):sc_pair_decrypt(PAIR(h),(uint8_t *)p,n,out,sizeof(out));
    (*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);if(count<0){fail(e,"Pairing failed; check the pairing code");return NULL;}return bytes(e,out,count);
}
