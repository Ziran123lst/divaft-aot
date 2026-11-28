// bbport: compatibility provider for std::__ndk1::__hash_memory(const void*, size_t).
//
// Some builds / system libraries compiled with newer libc++ reference this out-of-line
// helper for std::hash<string>. Our bundled libc++_shared.so (and every libbbgpu.so we
// ship) resolves it inline, so the symbol is normally never needed. Providing a weak
// definition here is a zero-cost safety net: Android's dynamic linker resolves the
// symbol in the global scope, so if anything on the load chain asks for it, this
// satisfies it; if nothing does, this is dead code. Weak means a libc++ build that
// does export the symbol still wins.
//
// Semantics: any deterministic byte hash is valid (std::hash only needs stability
// within one process). FNV-1a 64-bit matches libc++'s fallback quality well enough.
#include <cstddef>
#include <cstdint>

extern "C" __attribute__((visibility("default"), weak))
std::size_t bb_hash_memory_shim(const void* ptr, std::size_t len) noexcept {
    const auto* p = static_cast<const unsigned char*>(ptr);
    std::uint64_t h = 14695981039346656037ULL; // FNV offset basis
    for (std::size_t i = 0; i < len; ++i) {
        h ^= static_cast<std::uint64_t>(p[i]);
        h *= 1099511628211ULL; // FNV prime
    }
    return static_cast<std::size_t>(h);
}

// Export under the Itanium-mangled name libc++ uses.
extern "C" __attribute__((alias("bb_hash_memory_shim"), visibility("default"), weak))
std::size_t _ZNSt6__ndk113__hash_memoryEPKvm(const void*, std::size_t) noexcept;
