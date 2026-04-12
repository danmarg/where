package net.af0.where.e2ee

import java.text.Normalizer

internal actual fun normalizeName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
