#include "chacha20poly1305.h"

#include <limits.h>
#include <string.h>

#define POLY_MASK26 UINT32_C(0x3ffffff)

static uint32_t load32_le(const uint8_t *source) {
    return (uint32_t)source[0] | ((uint32_t)source[1] << 8) |
           ((uint32_t)source[2] << 16) | ((uint32_t)source[3] << 24);
}

static void store32_le(uint8_t *destination, uint32_t value) {
    destination[0] = (uint8_t)value;
    destination[1] = (uint8_t)(value >> 8);
    destination[2] = (uint8_t)(value >> 16);
    destination[3] = (uint8_t)(value >> 24);
}

static void store64_le(uint8_t *destination, uint64_t value) {
    store32_le(destination, (uint32_t)value);
    store32_le(destination + 4, (uint32_t)(value >> 32));
}

static uint32_t rotate_left(uint32_t value, unsigned int amount) {
    return (value << amount) | (value >> (32U - amount));
}

#define QUARTER_ROUND(a, b, c, d) \
    do { \
        (a) += (b); (d) ^= (a); (d) = rotate_left((d), 16); \
        (c) += (d); (b) ^= (c); (b) = rotate_left((b), 12); \
        (a) += (b); (d) ^= (a); (d) = rotate_left((d), 8); \
        (c) += (d); (b) ^= (c); (b) = rotate_left((b), 7); \
    } while (0)

void chacha20_block(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12], uint8_t output[64]) {
    static const uint8_t constants[16] = {
        'e', 'x', 'p', 'a', 'n', 'd', ' ', '3', '2', '-', 'b', 'y', 't', 'e', ' ', 'k',
    };
    uint32_t state[16];
    uint32_t working[16];
    size_t index;

    state[0] = load32_le(constants);
    state[1] = load32_le(constants + 4);
    state[2] = load32_le(constants + 8);
    state[3] = load32_le(constants + 12);
    for (index = 0; index < 8; ++index) state[4 + index] = load32_le(key + index * 4);
    state[12] = counter;
    state[13] = load32_le(nonce);
    state[14] = load32_le(nonce + 4);
    state[15] = load32_le(nonce + 8);
    memcpy(working, state, sizeof(state));

    for (index = 0; index < 10; ++index) {
        QUARTER_ROUND(working[0], working[4], working[8], working[12]);
        QUARTER_ROUND(working[1], working[5], working[9], working[13]);
        QUARTER_ROUND(working[2], working[6], working[10], working[14]);
        QUARTER_ROUND(working[3], working[7], working[11], working[15]);
        QUARTER_ROUND(working[0], working[5], working[10], working[15]);
        QUARTER_ROUND(working[1], working[6], working[11], working[12]);
        QUARTER_ROUND(working[2], working[7], working[8], working[13]);
        QUARTER_ROUND(working[3], working[4], working[9], working[14]);
    }

    for (index = 0; index < 16; ++index) store32_le(output + index * 4, working[index] + state[index]);
}

int chacha20_xor(const uint8_t key[32], const uint8_t nonce[12], uint32_t initial_counter,
                 const uint8_t *input, size_t input_length, uint8_t *output) {
    uint64_t block_count = (uint64_t)(input_length / 64U) + (input_length % 64U != 0U ? 1U : 0U);
    uint64_t available_blocks = (uint64_t)UINT32_MAX - (uint64_t)initial_counter + 1U;
    uint8_t block[64];
    size_t offset = 0;
    uint64_t block_index = 0;

    if (key == NULL || nonce == NULL || (input_length != 0U && (input == NULL || output == NULL)) ||
        block_count > available_blocks) return -1;

    while (offset < input_length) {
        size_t index;
        size_t chunk = input_length - offset;
        if (chunk > sizeof(block)) chunk = sizeof(block);
        chacha20_block(key, (uint32_t)((uint64_t)initial_counter + block_index), nonce, block);
        for (index = 0; index < chunk; ++index) output[offset + index] = input[offset + index] ^ block[index];
        offset += chunk;
        ++block_index;
    }
    return 0;
}

typedef struct {
    uint32_t r0, r1, r2, r3, r4;
    uint32_t s1, s2, s3, s4;
    uint32_t h0, h1, h2, h3, h4;
    uint32_t pad0, pad1, pad2, pad3;
    uint8_t buffer[16];
    size_t leftover;
} poly1305_state;

static void poly1305_init(poly1305_state *state, const uint8_t key[32]) {
    state->r0 = load32_le(key) & POLY_MASK26;
    state->r1 = (load32_le(key + 3) >> 2) & UINT32_C(0x3ffff03);
    state->r2 = (load32_le(key + 6) >> 4) & UINT32_C(0x3ffc0ff);
    state->r3 = (load32_le(key + 9) >> 6) & UINT32_C(0x3f03fff);
    state->r4 = (load32_le(key + 12) >> 8) & UINT32_C(0x00fffff);
    state->s1 = state->r1 * 5U;
    state->s2 = state->r2 * 5U;
    state->s3 = state->r3 * 5U;
    state->s4 = state->r4 * 5U;
    state->h0 = 0;
    state->h1 = 0;
    state->h2 = 0;
    state->h3 = 0;
    state->h4 = 0;
    state->pad0 = load32_le(key + 16);
    state->pad1 = load32_le(key + 20);
    state->pad2 = load32_le(key + 24);
    state->pad3 = load32_le(key + 28);
    state->leftover = 0;
}

static void poly1305_blocks(poly1305_state *state, const uint8_t *message, size_t bytes, uint32_t hibit) {
    uint32_t h0 = state->h0;
    uint32_t h1 = state->h1;
    uint32_t h2 = state->h2;
    uint32_t h3 = state->h3;
    uint32_t h4 = state->h4;
    const uint32_t r0 = state->r0;
    const uint32_t r1 = state->r1;
    const uint32_t r2 = state->r2;
    const uint32_t r3 = state->r3;
    const uint32_t r4 = state->r4;
    const uint32_t s1 = state->s1;
    const uint32_t s2 = state->s2;
    const uint32_t s3 = state->s3;
    const uint32_t s4 = state->s4;

    while (bytes >= 16U) {
        uint64_t d0, d1, d2, d3, d4;
        uint32_t carry;
        h0 += load32_le(message) & POLY_MASK26;
        h1 += (load32_le(message + 3) >> 2) & POLY_MASK26;
        h2 += (load32_le(message + 6) >> 4) & POLY_MASK26;
        h3 += (load32_le(message + 9) >> 6) & POLY_MASK26;
        h4 += (load32_le(message + 12) >> 8) | hibit;

        d0 = (uint64_t)h0 * r0 + (uint64_t)h1 * s4 + (uint64_t)h2 * s3 +
             (uint64_t)h3 * s2 + (uint64_t)h4 * s1;
        d1 = (uint64_t)h0 * r1 + (uint64_t)h1 * r0 + (uint64_t)h2 * s4 +
             (uint64_t)h3 * s3 + (uint64_t)h4 * s2;
        d2 = (uint64_t)h0 * r2 + (uint64_t)h1 * r1 + (uint64_t)h2 * r0 +
             (uint64_t)h3 * s4 + (uint64_t)h4 * s3;
        d3 = (uint64_t)h0 * r3 + (uint64_t)h1 * r2 + (uint64_t)h2 * r1 +
             (uint64_t)h3 * r0 + (uint64_t)h4 * s4;
        d4 = (uint64_t)h0 * r4 + (uint64_t)h1 * r3 + (uint64_t)h2 * r2 +
             (uint64_t)h3 * r1 + (uint64_t)h4 * r0;

        carry = (uint32_t)(d0 >> 26); h0 = (uint32_t)d0 & POLY_MASK26; d1 += carry;
        carry = (uint32_t)(d1 >> 26); h1 = (uint32_t)d1 & POLY_MASK26; d2 += carry;
        carry = (uint32_t)(d2 >> 26); h2 = (uint32_t)d2 & POLY_MASK26; d3 += carry;
        carry = (uint32_t)(d3 >> 26); h3 = (uint32_t)d3 & POLY_MASK26; d4 += carry;
        carry = (uint32_t)(d4 >> 26); h4 = (uint32_t)d4 & POLY_MASK26;
        h0 += carry * 5U;
        carry = h0 >> 26; h0 &= POLY_MASK26; h1 += carry;

        message += 16;
        bytes -= 16;
    }
    state->h0 = h0;
    state->h1 = h1;
    state->h2 = h2;
    state->h3 = h3;
    state->h4 = h4;
}

static void poly1305_update(poly1305_state *state, const uint8_t *message, size_t bytes) {
    if (state->leftover != 0U) {
        size_t want = 16U - state->leftover;
        if (want > bytes) want = bytes;
        if (want != 0U) memcpy(state->buffer + state->leftover, message, want);
        bytes -= want;
        message += want;
        state->leftover += want;
        if (state->leftover < 16U) return;
        poly1305_blocks(state, state->buffer, 16U, UINT32_C(1) << 24);
        state->leftover = 0;
    }

    if (bytes >= 16U) {
        size_t full_bytes = bytes & ~(size_t)0x0fU;
        poly1305_blocks(state, message, full_bytes, UINT32_C(1) << 24);
        message += full_bytes;
        bytes -= full_bytes;
    }
    if (bytes != 0U) {
        memcpy(state->buffer, message, bytes);
        state->leftover = bytes;
    }
}

static void poly1305_finish(poly1305_state *state, uint8_t tag[16]) {
    uint32_t h0, h1, h2, h3, h4;
    uint32_t g0, g1, g2, g3, g4;
    uint32_t carry, mask;
    uint64_t value;

    if (state->leftover != 0U) {
        state->buffer[state->leftover] = 1;
        memset(state->buffer + state->leftover + 1U, 0, 15U - state->leftover);
        poly1305_blocks(state, state->buffer, 16U, 0);
    }

    h0 = state->h0; h1 = state->h1; h2 = state->h2; h3 = state->h3; h4 = state->h4;
    carry = h1 >> 26; h1 &= POLY_MASK26; h2 += carry;
    carry = h2 >> 26; h2 &= POLY_MASK26; h3 += carry;
    carry = h3 >> 26; h3 &= POLY_MASK26; h4 += carry;
    carry = h4 >> 26; h4 &= POLY_MASK26; h0 += carry * 5U;
    carry = h0 >> 26; h0 &= POLY_MASK26; h1 += carry;

    g0 = h0 + 5U; carry = g0 >> 26; g0 &= POLY_MASK26;
    g1 = h1 + carry; carry = g1 >> 26; g1 &= POLY_MASK26;
    g2 = h2 + carry; carry = g2 >> 26; g2 &= POLY_MASK26;
    g3 = h3 + carry; carry = g3 >> 26; g3 &= POLY_MASK26;
    g4 = h4 + carry - (UINT32_C(1) << 26);

    mask = (g4 >> 31) - 1U;
    g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0;
    h1 = (h1 & mask) | g1;
    h2 = (h2 & mask) | g2;
    h3 = (h3 & mask) | g3;
    h4 = (h4 & mask) | g4;

    value = (uint64_t)(h0 | (h1 << 26)) + state->pad0;
    store32_le(tag, (uint32_t)value);
    value = (uint64_t)((h1 >> 6) | (h2 << 20)) + state->pad1 + (value >> 32);
    store32_le(tag + 4, (uint32_t)value);
    value = (uint64_t)((h2 >> 12) | (h3 << 14)) + state->pad2 + (value >> 32);
    store32_le(tag + 8, (uint32_t)value);
    value = (uint64_t)((h3 >> 18) | (h4 << 8)) + state->pad3 + (value >> 32);
    store32_le(tag + 12, (uint32_t)value);
}

void poly1305_auth(uint8_t tag[16], const uint8_t *message, size_t message_length,
                   const uint8_t key[32]) {
    poly1305_state state;
    if (tag == NULL || key == NULL || (message_length != 0U && message == NULL)) return;
    poly1305_init(&state, key);
    poly1305_update(&state, message, message_length);
    poly1305_finish(&state, tag);
}

static void poly1305_pad16(poly1305_state *state, size_t section_length) {
    static const uint8_t zeros[16] = {0};
    size_t remainder = section_length & 15U;
    if (remainder != 0U) poly1305_update(state, zeros, 16U - remainder);
}

static void poly1305_aead_tag(uint8_t tag[16], const uint8_t poly_key[32],
                              const uint8_t *aad, size_t aad_length,
                              const uint8_t *ciphertext, size_t ciphertext_length) {
    poly1305_state state;
    uint8_t lengths[16];
    poly1305_init(&state, poly_key);
    poly1305_update(&state, aad, aad_length);
    poly1305_pad16(&state, aad_length);
    poly1305_update(&state, ciphertext, ciphertext_length);
    poly1305_pad16(&state, ciphertext_length);
    store64_le(lengths, (uint64_t)aad_length);
    store64_le(lengths + 8, (uint64_t)ciphertext_length);
    poly1305_update(&state, lengths, sizeof(lengths));
    poly1305_finish(&state, tag);
}

static int valid_aead_arguments(const uint8_t key[32], const uint8_t nonce[12],
                                const uint8_t *aad, size_t aad_length,
                                const uint8_t *input, size_t input_length,
                                uint8_t *output, size_t output_length) {
    return key != NULL && nonce != NULL && (aad_length == 0U || aad != NULL) &&
           (input_length == 0U || input != NULL) && (output_length == 0U || output != NULL);
}

int chacha20poly1305_seal(const uint8_t key[32], const uint8_t nonce[12],
                          const uint8_t *aad, size_t aad_length,
                          const uint8_t *plaintext, size_t plaintext_length,
                          uint8_t *output, size_t output_capacity) {
    uint8_t first_block[64];
    uint8_t tag[16];
    size_t required;

    if (plaintext_length > SIZE_MAX - 16U) return -1;
    required = plaintext_length + 16U;
    if (!valid_aead_arguments(key, nonce, aad, aad_length, plaintext, plaintext_length, output, required) ||
        output_capacity < required) return -1;
    chacha20_block(key, 0, nonce, first_block);
    if (chacha20_xor(key, nonce, 1, plaintext, plaintext_length, output) != 0) return -1;
    poly1305_aead_tag(tag, first_block, aad, aad_length, output, plaintext_length);
    memcpy(output + plaintext_length, tag, sizeof(tag));
    return 0;
}

int chacha20poly1305_open(const uint8_t key[32], const uint8_t nonce[12],
                          const uint8_t *aad, size_t aad_length,
                          const uint8_t *ciphertext_and_tag, size_t input_length,
                          uint8_t *output, size_t output_capacity) {
    uint8_t first_block[64];
    uint8_t expected_tag[16];
    size_t ciphertext_length;
    uint8_t difference = 0;
    size_t index;

    if (input_length < 16U) return -1;
    ciphertext_length = input_length - 16U;
    if (!valid_aead_arguments(key, nonce, aad, aad_length, ciphertext_and_tag, input_length,
                              output, ciphertext_length) || output_capacity < ciphertext_length) return -1;
    chacha20_block(key, 0, nonce, first_block);
    poly1305_aead_tag(expected_tag, first_block, aad, aad_length, ciphertext_and_tag, ciphertext_length);
    for (index = 0; index < sizeof(expected_tag); ++index) {
        difference |= expected_tag[index] ^ ciphertext_and_tag[ciphertext_length + index];
    }
    if (difference != 0U) {
        if (ciphertext_length != 0U) memset(output, 0, ciphertext_length);
        return -1;
    }
    return chacha20_xor(key, nonce, 1, ciphertext_and_tag, ciphertext_length, output);
}
