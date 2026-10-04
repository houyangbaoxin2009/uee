package org.tielang.td;

/**
 * One table entry: a named key/value pair, or a keyless array element
 * (key == null). Package-internal.
 */
record TdEntry(String key, TdValue value) {
}