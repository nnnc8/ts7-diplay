#ifndef DIPLAY_CHACHA20POLY1305_H
#define DIPLAY_CHACHA20POLY1305_H

#include <stddef.h>
#include <stdint.h>

void chacha20_block(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12], uint8_t output[64]);
int chacha20_xor(const uint8_t key[32], const uint8_t nonce[12], uint32_t initial_counter,
                 const uint8_t *input, size_t input_length, uint8_t *output);
void poly1305_auth(uint8_t tag[16], const uint8_t *message, size_t message_length,
                   const uint8_t key[32]);

int chacha20poly1305_seal(const uint8_t key[32], const uint8_t nonce[12],
                          const uint8_t *aad, size_t aad_length,
                          const uint8_t *plaintext, size_t plaintext_length,
                          uint8_t *output, size_t output_capacity);
int chacha20poly1305_open(const uint8_t key[32], const uint8_t nonce[12],
                          const uint8_t *aad, size_t aad_length,
                          const uint8_t *ciphertext_and_tag, size_t input_length,
                          uint8_t *output, size_t output_capacity);

#endif
