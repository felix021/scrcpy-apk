// GPL-3.0. Certificate and pairing primitives adapted from lionlggnd/ADBClient-iOS
// AdbNative.c, via scrcpy-ios; see third_party/README.md. Wire constants follow AOSP adb.
#include "AdbCrypto.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/aead.h>
#include <openssl/curve25519.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>
#include <openssl/mem.h>
#include <openssl/rsa.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

struct sc_tls { SSL_CTX *ctx; SSL *ssl; };
struct sc_pair { SPAKE2_CTX *spake; uint8_t message[SPAKE2_MAX_MSG_SIZE]; size_t message_length; uint8_t key[16]; int ready; uint64_t tx, rx; };

static enum ssl_verify_result_t adb_peer(SSL *ssl, uint8_t *alert) {
    // ADB uses self-signed Android certificates, not web PKI. Android verifies
    // this client's paired RSA identity. PAKE binds pairing to the TLS exporter.
    (void)ssl; (void)alert; return ssl_verify_ok;
}
sc_tls *sc_tls_create(const uint8_t *der, size_t length) {
    ERR_clear_error();
    sc_tls *t = calloc(1, sizeof(*t));
    const uint8_t *cursor = der;
    RSA *rsa = d2i_RSAPrivateKey(NULL, &cursor, length);
    EVP_PKEY *key = EVP_PKEY_new(); X509 *cert = X509_new();
    if (!t || !rsa || !key || !cert || cursor != der + length) goto failed;
    if (!EVP_PKEY_assign_RSA(key, rsa)) goto failed;
    rsa = NULL;
    X509_set_version(cert, 2); ASN1_INTEGER_set(X509_get_serialNumber(cert), 1);
    X509_gmtime_adj(X509_getm_notBefore(cert), -60);
    X509_gmtime_adj(X509_getm_notAfter(cert), 10L * 365 * 24 * 3600);
    if (!X509_set_pubkey(cert, key)) goto failed;
    X509_NAME *name = X509_get_subject_name(cert);
    if (!X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, (const uint8_t *)"scrcpy", -1, -1, 0) || !X509_set_issuer_name(cert, name) || !X509_sign(cert, key, EVP_sha256())) goto failed;
    t->ctx = SSL_CTX_new(TLS_method());
    if (!t->ctx || !SSL_CTX_set_min_proto_version(t->ctx, TLS1_3_VERSION) || !SSL_CTX_set_max_proto_version(t->ctx, TLS1_3_VERSION) || !SSL_CTX_use_certificate(t->ctx, cert) || !SSL_CTX_use_PrivateKey(t->ctx, key)) goto failed;
    SSL_CTX_set_custom_verify(t->ctx, SSL_VERIFY_PEER, adb_peer);
    t->ssl = SSL_new(t->ctx);
    if (!t->ssl) goto failed;
    BIO *input = BIO_new(BIO_s_mem()), *output = BIO_new(BIO_s_mem());
    if (!input || !output) { BIO_free(input); BIO_free(output); goto failed; }
    BIO_set_mem_eof_return(input, -1); BIO_set_mem_eof_return(output, -1);
    SSL_set_bio(t->ssl, input, output); SSL_set_connect_state(t->ssl);
    X509_free(cert); EVP_PKEY_free(key); return t;
failed:
    RSA_free(rsa); X509_free(cert); EVP_PKEY_free(key); sc_tls_free(t); return NULL;
}
void sc_tls_free(sc_tls *t) { if (t) { SSL_free(t->ssl); SSL_CTX_free(t->ctx); free(t); } }
static int ssl_result(sc_tls *t, int result) {
    if (result > 0) return result;
    int error = SSL_get_error(t->ssl, result);
    return error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE ? -2 : error == SSL_ERROR_ZERO_RETURN ? 0 : -1;
}
int sc_tls_handshake(sc_tls *t) { ERR_clear_error(); int r = SSL_do_handshake(t->ssl); return r == 1 ? 1 : ssl_result(t, r) == -2 ? 0 : -1; }
int sc_tls_feed(sc_tls *t, const uint8_t *bytes, size_t length) { return length <= INT_MAX && BIO_write(SSL_get_rbio(t->ssl), bytes, (int)length) == (int)length; }
int sc_tls_drain(sc_tls *t, uint8_t *bytes, size_t capacity) { int r = BIO_read(SSL_get_wbio(t->ssl), bytes, capacity > INT_MAX ? INT_MAX : (int)capacity); return r > 0 ? r : 0; }
int sc_tls_read(sc_tls *t, uint8_t *bytes, size_t capacity) { ERR_clear_error(); return ssl_result(t, SSL_read(t->ssl, bytes, capacity > INT_MAX ? INT_MAX : (int)capacity)); }
int sc_tls_write(sc_tls *t, const uint8_t *bytes, size_t length) { ERR_clear_error(); return length <= INT_MAX ? ssl_result(t, SSL_write(t->ssl, bytes, (int)length)) : -1; }
int sc_tls_export(sc_tls *t, uint8_t out[64]) { static const char label[] = "adb-label"; return SSL_export_keying_material(t->ssl, out, 64, label, sizeof(label), NULL, 0, 0); }
void sc_tls_error(char *out, size_t capacity) { uint32_t e = ERR_get_error(); if (e) ERR_error_string_n(e, out, capacity); else if (capacity) { strncpy(out, "TLS connection closed or rejected", capacity - 1); out[capacity - 1] = 0; } }
sc_pair *sc_pair_create(const uint8_t *password, size_t length) {
    sc_pair *p = calloc(1, sizeof(*p)); if (!p) return NULL;
    static const uint8_t client[] = "adb pair client", server[] = "adb pair server";
    p->spake = SPAKE2_CTX_new(spake2_role_alice, client, sizeof(client), server, sizeof(server));
    if (!p->spake || !SPAKE2_generate_msg(p->spake, p->message, &p->message_length, sizeof(p->message), password, length)) { sc_pair_free(p); return NULL; }
    return p;
}
void sc_pair_free(sc_pair *p) { if (p) { SPAKE2_CTX_free(p->spake); OPENSSL_cleanse(p, sizeof(*p)); free(p); } }
int sc_pair_message(sc_pair *p, uint8_t *out, size_t capacity) { if (capacity < p->message_length) return -1; memcpy(out, p->message, p->message_length); return (int)p->message_length; }
int sc_pair_process(sc_pair *p, const uint8_t *message, size_t length) {
    uint8_t key[SPAKE2_MAX_KEY_SIZE]; size_t count = 0;
    static const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    if (p->ready || !p->spake || !length || length > SPAKE2_MAX_MSG_SIZE || !SPAKE2_process_msg(p->spake, key, &count, sizeof(key), message, length)) return 0;
    int ok = HKDF(p->key, sizeof(p->key), EVP_sha256(), key, count, NULL, 0, info, sizeof(info) - 1);
    OPENSSL_cleanse(key, sizeof(key)); SPAKE2_CTX_free(p->spake); p->spake = NULL; p->ready = ok; return ok;
}
static int crypt(sc_pair *p, int encrypt, const uint8_t *in, size_t length, uint8_t *out, size_t capacity) {
    if (!p->ready) return -1;
    uint64_t *sequence = encrypt ? &p->tx : &p->rx;
    uint8_t nonce[12] = {0}; for (int i = 0; i < 8; ++i) nonce[i] = (uint8_t)(*sequence >> (i * 8));
    EVP_AEAD_CTX ctx; size_t size = 0;
    if (!EVP_AEAD_CTX_init(&ctx, EVP_aead_aes_128_gcm(), p->key, sizeof(p->key), EVP_AEAD_DEFAULT_TAG_LENGTH, NULL)) return -1;
    int ok = encrypt ? EVP_AEAD_CTX_seal(&ctx, out, &size, capacity, nonce, sizeof(nonce), in, length, NULL, 0) : EVP_AEAD_CTX_open(&ctx, out, &size, capacity, nonce, sizeof(nonce), in, length, NULL, 0);
    EVP_AEAD_CTX_cleanup(&ctx); if (!ok) return -1; ++*sequence; return (int)size;
}
int sc_pair_encrypt(sc_pair *p, const uint8_t *in, size_t length, uint8_t *out, size_t capacity) { return crypt(p, 1, in, length, out, capacity); }
int sc_pair_decrypt(sc_pair *p, const uint8_t *in, size_t length, uint8_t *out, size_t capacity) { return crypt(p, 0, in, length, out, capacity); }
