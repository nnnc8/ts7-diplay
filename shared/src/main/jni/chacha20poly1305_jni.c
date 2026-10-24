#include <jni.h>

#include "chacha20poly1305.h"

#include <limits.h>
#include <stdint.h>

static jint transform(JNIEnv *env, jbyteArray key_array, jbyteArray nonce_array,
                      jbyteArray input_array, jint input_offset, jint input_length,
                      jbyteArray aad_array, jint aad_offset, jint aad_length,
                      jbyteArray output_array, int opening) {
    jsize key_size;
    jsize nonce_size;
    jsize input_size;
    jsize aad_size;
    jsize output_size;
    jbyte key[32];
    jbyte nonce[12];
    jbyte *input_bytes = NULL;
    jbyte *aad_bytes = NULL;
    jbyte *output_bytes = NULL;
    size_t expected_output;
    int result;

    if (key_array == NULL || nonce_array == NULL || input_array == NULL ||
        aad_array == NULL || output_array == NULL || input_offset < 0 || input_length < 0 ||
        aad_offset < 0 || aad_length < 0) return -2;

    key_size = (*env)->GetArrayLength(env, key_array);
    nonce_size = (*env)->GetArrayLength(env, nonce_array);
    input_size = (*env)->GetArrayLength(env, input_array);
    aad_size = (*env)->GetArrayLength(env, aad_array);
    output_size = (*env)->GetArrayLength(env, output_array);
    if (key_size != 32 || nonce_size != 12 || input_offset > input_size ||
        input_length > input_size - input_offset || aad_offset > aad_size ||
        aad_length > aad_size - aad_offset) return -2;
    if (opening && input_length < 16) return -2;
    if (!opening && input_length > INT_MAX - 16) return -2;

    expected_output = opening ? (size_t)(input_length - 16) : (size_t)input_length + 16U;
    if ((size_t)output_size < expected_output) return -2;

    (*env)->GetByteArrayRegion(env, key_array, 0, 32, key);
    (*env)->GetByteArrayRegion(env, nonce_array, 0, 12, nonce);
    if ((*env)->ExceptionCheck(env)) return -2;

    if (input_length != 0) {
        input_bytes = (*env)->GetPrimitiveArrayCritical(env, input_array, NULL);
        if (input_bytes == NULL) return -2;
    }
    if (aad_length != 0) {
        aad_bytes = (*env)->GetPrimitiveArrayCritical(env, aad_array, NULL);
        if (aad_bytes == NULL) {
            if (input_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, input_array, input_bytes, JNI_ABORT);
            return -2;
        }
    }
    if (expected_output != 0U) {
        output_bytes = (*env)->GetPrimitiveArrayCritical(env, output_array, NULL);
        if (output_bytes == NULL) {
            if (aad_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, aad_array, aad_bytes, JNI_ABORT);
            if (input_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, input_array, input_bytes, JNI_ABORT);
            return -2;
        }
    }

    if (opening) {
        result = chacha20poly1305_open((const uint8_t *)key, (const uint8_t *)nonce,
                                       aad_bytes == NULL ? NULL : (const uint8_t *)aad_bytes + aad_offset,
                                       (size_t)aad_length,
                                       input_bytes == NULL ? NULL : (const uint8_t *)input_bytes + input_offset,
                                       (size_t)input_length, (uint8_t *)output_bytes, expected_output);
    } else {
        result = chacha20poly1305_seal((const uint8_t *)key, (const uint8_t *)nonce,
                                       aad_bytes == NULL ? NULL : (const uint8_t *)aad_bytes + aad_offset,
                                       (size_t)aad_length,
                                       input_bytes == NULL ? NULL : (const uint8_t *)input_bytes + input_offset,
                                       (size_t)input_length, (uint8_t *)output_bytes, expected_output);
    }

    if (output_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, output_array, output_bytes, 0);
    if (aad_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, aad_array, aad_bytes, JNI_ABORT);
    if (input_bytes != NULL) (*env)->ReleasePrimitiveArrayCritical(env, input_array, input_bytes, JNI_ABORT);
    return result == 0 ? (jint)expected_output : -1;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha20Poly1305_nativeSeal(
        JNIEnv *env, jobject receiver, jbyteArray key, jbyteArray nonce,
        jbyteArray input, jint input_offset, jint input_length,
        jbyteArray aad, jint aad_offset, jint aad_length, jbyteArray output) {
    (void)receiver;
    return transform(env, key, nonce, input, input_offset, input_length,
                     aad, aad_offset, aad_length, output, 0);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha20Poly1305_nativeOpen(
        JNIEnv *env, jobject receiver, jbyteArray key, jbyteArray nonce,
        jbyteArray input, jint input_offset, jint input_length,
        jbyteArray aad, jint aad_offset, jint aad_length, jbyteArray output) {
    (void)receiver;
    return transform(env, key, nonce, input, input_offset, input_length,
                     aad, aad_offset, aad_length, output, 1);
}
