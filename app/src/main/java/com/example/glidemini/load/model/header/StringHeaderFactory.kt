package com.example.glidemini.load.model.header

class StringHeaderFactory(private val value: String) : LazyHeaderFactory {
    override fun buildHeader(): String {
        return value
    }

    override fun toString(): String {
        return "StringHeaderFactory{value='$value'}"
    }

    override fun equals(other: Any?): Boolean {
        if (other is StringHeaderFactory) {
            return value == other.value
        }
        return false
    }
    override fun hashCode(): Int {
        return value.hashCode()
    }
}
