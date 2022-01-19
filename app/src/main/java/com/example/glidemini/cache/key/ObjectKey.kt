package com.example.glidemini.cache.key

import java.security.MessageDigest

/**
 * 一个ObjectKey
 */
class ObjectKey(val obj: Any) : Key {

    override fun equals(other: Any?): Boolean {
        if (other is ObjectKey) {
            return obj == other.obj
        }
        return false
    }

    override fun hashCode(): Int {
        return obj.hashCode()
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(obj.toString().toByteArray(Key.CHARSET))
    }
}