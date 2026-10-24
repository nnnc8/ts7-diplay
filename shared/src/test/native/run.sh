#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
cd "$script_dir"

clang -std=c99 -O2 -Wall -Wextra -Werror -fsanitize=address,undefined \
    -fno-omit-frame-pointer -D_POSIX_C_SOURCE=200809L \
    ../../main/jni/chacha20poly1305.c chacha20poly1305_test.c \
    -o "$build_dir/chacha20poly1305_test"
"$build_dir/chacha20poly1305_test"
