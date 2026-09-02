#!/usr/bin/env bash
# Binary-compatibility check: does every libreforge/eco method our jar links against exist, with the
# exact same descriptor, in the jars the server will actually run?
#
#   tools/libreforge-abi-check.sh <our-plugin.jar> <dependency.jar> [<dependency.jar> ...]
#
# Why this exists: libreforge is Kotlin, and a Kotlin function with default arguments is called
# through a synthetic `name$default` bridge whose signature lists every parameter. Auxilor adds
# parameters without notice (2026.35.1 added three to ConfigArgumentsBuilder.require and one to
# Trigger.dispatch). The source still compiles, so a green build proves nothing; the failure is a
# NoSuchMethodError at runtime — at boot if the call is in a static initialiser, or on the first
# island trigger if it is not. This script fails the same way the server would, before the server.
#
# Only classes under com/mystipixel/ are scanned, and only references into com.willfp.* that are not
# satisfied by a class bundled in our own jar (the relocated libreforge loader, for instance).
# Descriptors are compared with the relocated Kotlin package (com/willfp/eco/libs/kotlin) folded back
# to kotlin/, so the server's shaded jar and a Gradle-cache jar compare alike.
#
# Exit 0: every reference resolved. Exit 1: at least one is missing — the output names it.

set -uo pipefail

if [ $# -lt 2 ]; then
    echo "usage: $0 <our-plugin.jar> <dependency.jar> [<dependency.jar> ...]" >&2
    exit 2
fi

ours="$1"; shift
deps=("$@")
for j in "$ours" "${deps[@]}"; do
    [ -f "$j" ] || { echo "not a file: $j" >&2; exit 2; }
done

# javap on Windows emits CRLF; strip it, and fold the relocated Kotlin package back to kotlin/.
clean() { tr -d '\r' | sed 's#com/willfp/eco/libs/kotlin/#kotlin/#g; s#com\.willfp\.eco\.libs\.kotlin\.#kotlin.#g'; }

# Owner prefixes we verify. Everything else (Paper, JDK, Kotlin stdlib, our own classes) is skipped.
owner_re='^com/willfp/(libreforge|eco)/'

# Classes bundled in our own jar — references to these are internal, not external.
bundled=$(unzip -Z1 "$ours" | grep '\.class$' | sed 's#\.class$##' | clean | sort -u)
is_bundled() { grep -qxF "$1" <<< "$bundled"; }

# --- 1. every external method reference from our own classes ------------------------------------
# One line per unique "owner.name:descriptor", drawn from the constant pool of each class.
own_classes=$(grep '^com/mystipixel/' <<< "$bundled" | sed 's#/#.#g')
refs=$(
    for c in $own_classes; do
        javap -v -cp "$ours" "$c" 2>/dev/null \
          | clean \
          | grep -E '= (Interface)?Methodref' \
          | sed -E 's#.*// ##' \
          | grep -E "$owner_re"
    done | sort -u
)

# --- 2. resolve each against the dependency jars ------------------------------------------------
# Methods are looked up on the owner, then on its superclasses and interfaces (javap prints both).
declare -A method_cache parent_cache
load_class() {   # <class, slash form> -> fills caches
    local cls="$1"
    [ -n "${method_cache[$cls]+x}" ] && return
    local dotted="${cls//\//.}" out=""
    for j in "${deps[@]}"; do
        out=$(javap -p -s -cp "$j" "$dotted" 2>/dev/null | clean)
        [ -n "$out" ] && break
    done
    # javap -s prints each member line, then "    descriptor: (...)". Pair them; constructors print
    # as the class name and become "<init>" to match the constant-pool form.
    method_cache[$cls]=$(printf '%s\n' "$out" | awk '
        /^  .*\(.*\)( throws .*)?;$/ {
            n=$0; sub(/\(.*/, "", n); sub(/.* /, "", n)
            if (n ~ /\./) n="\"<init>\""
            name=n; next
        }
        /^ *descriptor: / { print name ":" $2 }
    ')
    # extends X implements A,B — collected as parents to walk.
    parent_cache[$cls]=$(printf '%s\n' "$out" | head -3 \
        | grep -oE '(extends|implements) [A-Za-z0-9_.$,<>? ]+' \
        | sed -E 's/^(extends|implements) //; s/<[^<>]*>//g; s/<[^<>]*>//g; s/<[^<>]*>//g; s/<[^<>]*>//g; s/,/ /g' | tr ' ' '\n' | grep -v '^$' | sed 's#\.#/#g')
}

resolves() {   # <owner> <name:descriptor> -> 0 if found on owner or any ancestor
    local sig="$2" queue="$1" seen="" cls
    while [ -n "$queue" ]; do
        cls="${queue%% *}"
        queue="${queue#"$cls"}"; queue="${queue# }"
        case " $seen " in *" $cls "*) continue;; esac
        seen="$seen $cls"
        load_class "$cls"
        if grep -qxF "$sig" <<< "${method_cache[$cls]}"; then return 0; fi
        [ -n "${parent_cache[$cls]}" ] && queue="$queue $(tr '\n' ' ' <<< "${parent_cache[$cls]}")"
        queue="${queue# }"
    done
    return 1
}

missing=0; checked=0
while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    owner="${ref%%.*}"
    sig="${ref#*.}"
    is_bundled "$owner" && continue
    checked=$((checked + 1))
    if resolves "$owner" "$sig"; then
        printf 'ok       %s\n' "$ref"
    else
        printf 'MISSING  %s\n' "$ref"
        missing=$((missing + 1))
    fi
done <<< "$refs"

echo
echo "checked $checked reference(s) into com.willfp.*: $missing missing"
[ "$missing" -eq 0 ]
