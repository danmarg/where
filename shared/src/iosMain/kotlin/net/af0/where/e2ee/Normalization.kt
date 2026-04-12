package net.af0.where.e2ee

import platform.Foundation.*

internal actual fun normalizeName(name: String): String {
    return (name as NSString).precomposedStringWithCompatibilityMapping()
}
