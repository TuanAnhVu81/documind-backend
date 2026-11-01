#!/bin/sh
# Run as root inside the existing official postgres:15-alpine container.
# Adds extension binaries only; does not create/drop/alter any existing database.
set -eu
version=0.8.7
if test -f "$(pg_config --sharedir)/extension/vector--${version}.sql"; then
  echo "pgvector ${version} is already installed"
  exit 0
fi
build_dir=$(mktemp -d /tmp/documind-pgvector.XXXXXX)
cleanup() {
  case "$build_dir" in
    /tmp/documind-pgvector.*) rm -rf -- "$build_dir" ;;
    *) echo 'Refusing to remove unexpected build path' >&2 ;;
  esac
}
trap cleanup EXIT
apk add --no-cache --virtual .documind-pgvector-build build-base ca-certificates wget
mkdir "$build_dir/src"
wget -q -O "$build_dir/source.tar.gz" "https://github.com/pgvector/pgvector/archive/refs/tags/v${version}.tar.gz"
tar -xzf "$build_dir/source.tar.gz" --strip-components=1 -C "$build_dir/src"
# Disable LLVM bitcode and CPU-specific instructions for this small local build.
make -C "$build_dir/src" OPTFLAGS='' with_llvm=no
make -C "$build_dir/src" OPTFLAGS='' with_llvm=no install
apk del .documind-pgvector-build
echo "Installed pgvector ${version}; existing databases are unchanged"
