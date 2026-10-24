#define _POSIX_C_SOURCE 200809L

#include "../../main/jni/chacha20poly1305.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static unsigned int assertions;
static unsigned int failures;

static void check(int condition, const char *name) {
    ++assertions;
    if (!condition) {
        ++failures;
        fprintf(stderr, "FAIL: %s\n", name);
    }
}

static int decode_hex(const char *text, uint8_t *output, size_t output_capacity) {
    size_t length;
    size_t index;
    if (strcmp(text, "-") == 0) return 0;
    length = strlen(text);
    if ((length & 1U) != 0U || length / 2U > output_capacity) return -1;
    for (index = 0; index < length / 2U; ++index) {
        unsigned int value;
        if (sscanf(text + index * 2U, "%2x", &value) != 1) return -1;
        output[index] = (uint8_t)value;
    }
    return (int)(length / 2U);
}

static void test_chacha20_block(void) {
    uint8_t key[32];
    uint8_t nonce[12];
    uint8_t expected[64];
    uint8_t actual[64];
    check(decode_hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", key, sizeof(key)) == 32,
          "RFC 8439 2.3.2 key decodes");
    check(decode_hex("000000090000004a00000000", nonce, sizeof(nonce)) == 12,
          "RFC 8439 2.3.2 nonce decodes");
    check(decode_hex("10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e"
                     "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
                     expected, sizeof(expected)) == 64, "RFC 8439 2.3.2 block decodes");
    chacha20_block(key, 1, nonce, actual);
    check(memcmp(actual, expected, sizeof(actual)) == 0, "RFC 8439 2.3.2 ChaCha20 block");
}

static void test_chacha20_encryption(void) {
    static const char plaintext[] =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    uint8_t key[32];
    uint8_t nonce[12];
    uint8_t expected[sizeof(plaintext) - 1U];
    uint8_t actual[sizeof(plaintext) - 1U];
    check(decode_hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", key, sizeof(key)) == 32,
          "RFC 8439 2.4.2 key decodes");
    check(decode_hex("000000000000004a00000000", nonce, sizeof(nonce)) == 12,
          "RFC 8439 2.4.2 nonce decodes");
    check(decode_hex("6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b"
                     "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8"
                     "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365"
                     "af90bbf74a35be6b40b8eedf2785e42874d",
                     expected, sizeof(expected)) == (int)sizeof(expected), "RFC 8439 2.4.2 ciphertext decodes");
    check(chacha20_xor(key, nonce, 1, (const uint8_t *)plaintext, sizeof(plaintext) - 1U, actual) == 0,
          "RFC 8439 2.4.2 encryption succeeds");
    check(memcmp(actual, expected, sizeof(actual)) == 0, "RFC 8439 2.4.2 ChaCha20 encryption");
}

static void test_poly1305(void) {
    uint8_t key[32];
    uint8_t tag[16];
    uint8_t expected[16];
    static const uint8_t message[] = "Cryptographic Forum Research Group";
    check(decode_hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b", key, sizeof(key)) == 32,
          "RFC 8439 2.5.2 key decodes");
    check(decode_hex("a8061dc1305136c6c22b8baf0c0127a9", expected, sizeof(expected)) == 16,
          "RFC 8439 2.5.2 tag decodes");
    poly1305_auth(tag, message, sizeof(message) - 1U, key);
    check(memcmp(tag, expected, sizeof(tag)) == 0, "RFC 8439 2.5.2 Poly1305");

    memset(key, 0, sizeof(key));
    key[0] = 2;
    memset(expected, 0, sizeof(expected));
    expected[0] = 3;
    {
        uint8_t data[16];
        memset(data, 0xff, sizeof(data));
        poly1305_auth(tag, data, sizeof(data), key);
        check(memcmp(tag, expected, sizeof(tag)) == 0, "RFC 8439 Appendix A.3 vector 5");
    }
}

static void test_aead_vector(void) {
    static const char plaintext[] =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    uint8_t key[32];
    uint8_t nonce[12];
    uint8_t aad[12];
    uint8_t expected[sizeof(plaintext) - 1U + 16U];
    uint8_t sealed[sizeof(expected)];
    uint8_t opened[sizeof(plaintext) - 1U];
    check(decode_hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f", key, sizeof(key)) == 32,
          "RFC 8439 2.8.2 key decodes");
    check(decode_hex("070000004041424344454647", nonce, sizeof(nonce)) == 12,
          "RFC 8439 2.8.2 nonce decodes");
    check(decode_hex("50515253c0c1c2c3c4c5c6c7", aad, sizeof(aad)) == 12,
          "RFC 8439 2.8.2 AAD decodes");
    check(decode_hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"
                     "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36"
                     "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc"
                     "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
                     expected, sizeof(expected)) == (int)sizeof(expected), "RFC 8439 2.8.2 output decodes");
    check(chacha20poly1305_seal(key, nonce, aad, sizeof(aad), (const uint8_t *)plaintext,
                                sizeof(plaintext) - 1U, sealed, sizeof(sealed)) == 0,
          "RFC 8439 2.8.2 seal succeeds");
    check(memcmp(sealed, expected, sizeof(sealed)) == 0, "RFC 8439 2.8.2 AEAD seal");
    check(chacha20poly1305_open(key, nonce, aad, sizeof(aad), sealed, sizeof(sealed),
                                opened, sizeof(opened)) == 0, "RFC 8439 2.8.2 open succeeds");
    check(memcmp(opened, plaintext, sizeof(opened)) == 0, "RFC 8439 2.8.2 AEAD open");

    sealed[sizeof(sealed) - 1U] ^= 1U;
    memset(opened, 0xa5, sizeof(opened));
    check(chacha20poly1305_open(key, nonce, aad, sizeof(aad), sealed, sizeof(sealed),
                                opened, sizeof(opened)) != 0, "tampered AEAD tag is rejected");
    {
        size_t index;
        int zeroed = 1;
        for (index = 0; index < sizeof(opened); ++index) zeroed &= opened[index] == 0;
        check(zeroed, "failed AEAD open clears plaintext output");
    }
}

static int read_fixture_case(FILE *fixture, unsigned int *line_number,
                             uint8_t **key, uint8_t **nonce, uint8_t **aad, size_t *aad_length,
                             uint8_t **plaintext, size_t *plaintext_length,
                             uint8_t **sealed, size_t *sealed_length) {
    char line[25000];
    char key_hex[65], nonce_hex[25], aad_hex[82], plaintext_hex[12001], sealed_hex[12033];
    int key_length, nonce_length, parsed_aad, parsed_plaintext, parsed_sealed;
    while (fgets(line, sizeof(line), fixture) != NULL) {
        ++*line_number;
        if (line[0] == '#' || line[0] == '\n') continue;
        if (sscanf(line, "%64s %24s %80s %12000s %12032s", key_hex, nonce_hex, aad_hex,
                   plaintext_hex, sealed_hex) != 5) return -1;
        *key = (uint8_t *)malloc(32U);
        *nonce = (uint8_t *)malloc(12U);
        parsed_aad = strcmp(aad_hex, "-") == 0 ? 0 : (int)(strlen(aad_hex) / 2U);
        parsed_plaintext = strcmp(plaintext_hex, "-") == 0 ? 0 : (int)(strlen(plaintext_hex) / 2U);
        parsed_sealed = (int)(strlen(sealed_hex) / 2U);
        *aad = (uint8_t *)malloc(parsed_aad == 0 ? 1U : (size_t)parsed_aad);
        *plaintext = (uint8_t *)malloc(parsed_plaintext == 0 ? 1U : (size_t)parsed_plaintext);
        *sealed = (uint8_t *)malloc((size_t)parsed_sealed);
        if (*key == NULL || *nonce == NULL || *aad == NULL || *plaintext == NULL || *sealed == NULL) return -1;
        key_length = decode_hex(key_hex, *key, 32U);
        nonce_length = decode_hex(nonce_hex, *nonce, 12U);
        parsed_aad = decode_hex(aad_hex, *aad, parsed_aad == 0 ? 1U : (size_t)parsed_aad);
        parsed_plaintext = decode_hex(plaintext_hex, *plaintext,
                                      parsed_plaintext == 0 ? 1U : (size_t)parsed_plaintext);
        parsed_sealed = decode_hex(sealed_hex, *sealed, (size_t)parsed_sealed);
        if (key_length != 32 || nonce_length != 12 || parsed_aad < 0 || parsed_plaintext < 0 || parsed_sealed < 16) return -1;
        *aad_length = (size_t)parsed_aad;
        *plaintext_length = (size_t)parsed_plaintext;
        *sealed_length = (size_t)parsed_sealed;
        return 1;
    }
    return 0;
}

static void test_bouncycastle_fixtures(void) {
    FILE *fixture = fopen("bc_vectors.txt", "r");
    unsigned int line_number = 0;
    unsigned int vector_count = 0;
    check(fixture != NULL, "BouncyCastle fixture opens");
    if (fixture == NULL) return;
    for (;;) {
        uint8_t *key = NULL, *nonce = NULL, *aad = NULL, *plaintext = NULL, *expected = NULL;
        uint8_t *sealed = NULL, *opened = NULL;
        size_t aad_length = 0, plaintext_length = 0, expected_length = 0;
        int status = read_fixture_case(fixture, &line_number, &key, &nonce, &aad, &aad_length,
                                       &plaintext, &plaintext_length, &expected, &expected_length);
        if (status == 0) break;
        if (status < 0) {
            check(0, "BouncyCastle fixture parses");
            free(key); free(nonce); free(aad); free(plaintext); free(expected);
            break;
        }
        ++vector_count;
        sealed = (uint8_t *)malloc(expected_length);
        opened = (uint8_t *)malloc(plaintext_length == 0U ? 1U : plaintext_length);
        if (sealed == NULL || opened == NULL) {
            check(0, "BouncyCastle fixture buffers allocate");
        } else {
            check(chacha20poly1305_seal(key, nonce, aad_length == 0U ? NULL : aad, aad_length,
                                        plaintext_length == 0U ? NULL : plaintext, plaintext_length,
                                        sealed, expected_length) == 0,
                  "BouncyCastle fixture seal succeeds");
            check(expected_length == plaintext_length + 16U && memcmp(sealed, expected, expected_length) == 0,
                  "native seal matches BouncyCastle fixture");
            check(chacha20poly1305_open(key, nonce, aad_length == 0U ? NULL : aad, aad_length,
                                        expected, expected_length, opened, plaintext_length) == 0,
                  "BouncyCastle fixture open succeeds");
            check(memcmp(opened, plaintext, plaintext_length) == 0, "native open matches BouncyCastle fixture");
            expected[expected_length - 1U] ^= 1U;
            memset(opened, 0xa5, plaintext_length);
            check(chacha20poly1305_open(key, nonce, aad_length == 0U ? NULL : aad, aad_length,
                                        expected, expected_length, opened, plaintext_length) != 0,
                  "BouncyCastle fixture flipped tag fails");
            {
                size_t index;
                int zeroed = 1;
                for (index = 0; index < plaintext_length; ++index) zeroed &= opened[index] == 0;
                check(zeroed, "BouncyCastle fixture failed output is zeroed");
            }
        }
        free(key); free(nonce); free(aad); free(plaintext); free(expected); free(sealed); free(opened);
    }
    fclose(fixture);
    check(vector_count == 60U, "all 60 BouncyCastle fixtures were checked");
    printf("BouncyCastle cross-check vectors: %u\n", vector_count);
}

static double seconds_between(const struct timespec *start, const struct timespec *end) {
    return (double)(end->tv_sec - start->tv_sec) + (double)(end->tv_nsec - start->tv_nsec) / 1000000000.0;
}

static void benchmark_host(void) {
    const size_t buffer_size = 64U * 1024U;
    const unsigned int iterations = 400U;
    uint8_t key[32] = {0};
    uint8_t nonce[12] = {0};
    uint8_t aad[12] = {0};
    uint8_t *plaintext = (uint8_t *)calloc(buffer_size, 1U);
    uint8_t *sealed = (uint8_t *)malloc(buffer_size + 16U);
    struct timespec start, end;
    unsigned int iteration;
    double elapsed;
    if (plaintext == NULL || sealed == NULL) {
        check(0, "benchmark buffers allocate");
        free(plaintext); free(sealed);
        return;
    }
    (void)clock_gettime(CLOCK_MONOTONIC, &start);
    for (iteration = 0; iteration < iterations; ++iteration) {
        nonce[8] = (uint8_t)iteration;
        nonce[9] = (uint8_t)(iteration >> 8);
        nonce[10] = (uint8_t)(iteration >> 16);
        nonce[11] = (uint8_t)(iteration >> 24);
        if (chacha20poly1305_seal(key, nonce, aad, sizeof(aad), plaintext, buffer_size,
                                  sealed, buffer_size + 16U) != 0) {
            check(0, "benchmark AEAD seal");
            free(plaintext); free(sealed);
            return;
        }
    }
    (void)clock_gettime(CLOCK_MONOTONIC, &end);
    elapsed = seconds_between(&start, &end);
    printf("host benchmark: 64 KiB AEAD seal %.1f MB/s (Mac host, not TS7)\n",
           ((double)buffer_size * iterations) / elapsed / 1000000.0);
    free(plaintext);
    free(sealed);
}

int main(void) {
    test_chacha20_block();
    test_chacha20_encryption();
    test_poly1305();
    test_aead_vector();
    test_bouncycastle_fixtures();
    benchmark_host();
    if (failures != 0U) {
        fprintf(stderr, "native ChaCha20-Poly1305: %u/%u checks failed\n", failures, assertions);
        return 1;
    }
    printf("native ChaCha20-Poly1305: %u checks passed, 0 failed\n", assertions);
    return 0;
}
