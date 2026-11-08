#ifndef SCRCPY_ADB_CRYPTO_H
#define SCRCPY_ADB_CRYPTO_H
#include <stddef.h>
#include <stdint.h>
typedef struct sc_tls sc_tls;
typedef struct sc_pair sc_pair;
// All TLS operations are nonblocking memory-BIO steps. The caller serializes each
// step and its outgoing records; network waits happen outside that lock.
sc_tls *sc_tls_create(const uint8_t *rsa_der, size_t length);
void sc_tls_free(sc_tls *tls);
int sc_tls_handshake(sc_tls *tls); // 1 complete, 0 needs input, -1 failed
int sc_tls_feed(sc_tls *tls, const uint8_t *bytes, size_t length);
int sc_tls_drain(sc_tls *tls, uint8_t *bytes, size_t capacity);
int sc_tls_read(sc_tls *tls, uint8_t *bytes, size_t capacity); // -2 needs input
int sc_tls_write(sc_tls *tls, const uint8_t *bytes, size_t length);
int sc_tls_export(sc_tls *tls, uint8_t out[64]);
void sc_tls_error(char *out, size_t capacity);
sc_pair *sc_pair_create(const uint8_t *password, size_t length);
void sc_pair_free(sc_pair *pair);
int sc_pair_message(sc_pair *pair, uint8_t *out, size_t capacity);
int sc_pair_process(sc_pair *pair, const uint8_t *message, size_t length);
int sc_pair_encrypt(sc_pair *pair, const uint8_t *in, size_t length, uint8_t *out, size_t capacity);
int sc_pair_decrypt(sc_pair *pair, const uint8_t *in, size_t length, uint8_t *out, size_t capacity);
#endif
