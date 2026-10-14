package com.acmeaom.myradar.sdkdemo.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Null-aware helpers for org.json, whose opt* accessors return "" or NaN for missing values.
 */

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else (opt(key) as? String)?.takeIf { it.isNotEmpty() }

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else (opt(key) as? Number)?.toDouble()

internal fun JSONArray.optDoubleOrNull(index: Int): Double? =
    if (isNull(index)) null else (opt(index) as? Number)?.toDouble()

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
