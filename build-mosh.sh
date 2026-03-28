#!/bin/bash
# Build mosh-client for Android arm64-v8a
# Prerequisites: Android NDK, autotools, pkg-config, protobuf
#
# Usage: ./build-mosh.sh [NDK_PATH]
#
# This script cross-compiles mosh-client and places the binary at:
#   app/src/main/assets/bin/mosh-client

set -e

NDK="${1:-$ANDROID_NDK_HOME}"
if [ -z "$NDK" ]; then
    echo "Usage: $0 <NDK_PATH>"
    echo "Or set ANDROID_NDK_HOME environment variable"
    exit 1
fi

API=28  # getrandom() requires API 28+
ARCH=aarch64
TARGET=aarch64-linux-android
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

export PATH="$TOOLCHAIN/bin:$PATH"
export ANDROID_NDK_ROOT="$NDK"
export CC="${TARGET}${API}-clang"
export CXX="${TARGET}${API}-clang++"
export AR="llvm-ar"
export RANLIB="llvm-ranlib"
export STRIP="llvm-strip"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="$(pwd)/build-mosh-tmp"
PREFIX="$WORKDIR/install"
mkdir -p "$WORKDIR" "$PREFIX"

echo "=== Building dependencies ==="

# Build protobuf (required by mosh)
cd "$WORKDIR"
if [ ! -d protobuf ]; then
    git clone --depth 1 --branch v3.21.12 https://github.com/protocolbuffers/protobuf.git
fi
cd protobuf
if [ ! -f "$PREFIX/lib/libprotobuf.a" ]; then
    git submodule update --init --recursive
    ./autogen.sh
    ./configure --host=$TARGET --prefix="$PREFIX" \
        --disable-shared --enable-static \
        --with-protoc="$(which protoc)" \
        CFLAGS="-fPIC" CXXFLAGS="-fPIC"
    make -j$(nproc) install
fi

# Build OpenSSL (required by mosh for AES-OCB)
cd "$WORKDIR"
if [ ! -d openssl ]; then
    git clone --depth 1 --branch openssl-3.2.0 https://github.com/openssl/openssl.git
fi
cd openssl
if [ ! -f "$PREFIX/lib/libcrypto.a" ]; then
    export ANDROID_NDK_ROOT="$NDK"
    ./Configure android-arm64 --prefix="$PREFIX" no-shared no-tests no-apps
    # Single-threaded: OpenSSL parallel make has race conditions with .d.tmp files
    make -j1 install_sw
fi

# Build ncurses (required by mosh for terminal)
cd "$WORKDIR"
if [ ! -d ncurses ]; then
    curl -sL https://ftp.gnu.org/gnu/ncurses/ncurses-6.4.tar.gz | tar xz
    mv ncurses-6.4 ncurses
fi
cd ncurses
if [ ! -f "$PREFIX/lib/libncurses.a" ]; then
    ./configure --host=$TARGET --prefix="$PREFIX" \
        --disable-shared --enable-static \
        --without-debug --without-ada --without-manpages \
        --without-progs --without-tests --without-cxx-binding \
        CFLAGS="-fPIC"
    make -j$(nproc) install
fi

echo "=== Building mosh ==="

cd "$WORKDIR"
if [ ! -d mosh ]; then
    git clone --depth 1 https://github.com/mobile-shell/mosh.git
fi
cd mosh

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

if [ ! -f configure ]; then
    ./autogen.sh
fi

./configure --host=$TARGET --prefix="$PREFIX" \
    --enable-client --disable-server \
    --with-crypto-library=openssl \
    PROTOC="$(which protoc)" \
    CPPFLAGS="-I$PREFIX/include -I$PREFIX/include/ncurses" \
    LDFLAGS="-L$PREFIX/lib -static-libgcc -static-libstdc++" \
    LIBS="-ldl -llog" \
    protobuf_CFLAGS="-I$PREFIX/include" \
    protobuf_LIBS="-L$PREFIX/lib -lprotobuf" \
    openssl_CFLAGS="-I$PREFIX/include" \
    openssl_LIBS="-L$PREFIX/lib -lssl -lcrypto" \
    TINFO_LIBS="-L$PREFIX/lib -lncurses"

make -j$(nproc)

echo "=== Installing ==="

OUTPUT="$SCRIPT_DIR/app/src/main/assets/bin"
mkdir -p "$OUTPUT"
$STRIP src/frontend/mosh-client -o "$OUTPUT/mosh-client"

ls -la "$OUTPUT/mosh-client"
echo "=== Done! mosh-client built at $OUTPUT/mosh-client ==="
