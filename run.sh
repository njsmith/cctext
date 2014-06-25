#!/bin/bash


DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

export LD_LIBRARY_PATH="$DIR/build/binaries/cLD2wrapSharedLibrary:$DIR/cld2/internal:$LD_LIBRARY_PATH"
exec rlwrap -a build/install/cctext/bin/cctext "$@"
