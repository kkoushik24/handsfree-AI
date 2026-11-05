package com.example.handsfree_ai.accessibility

/** True when a node's descendants are omitted by the configured per-node traversal bounds. */
internal fun omitsChildrenAtTraversalBounds(childCount: Int, depth: Int, maxChildren: Int, maxDepth: Int): Boolean =
    childCount > maxChildren || (childCount > 0 && depth >= maxDepth)
