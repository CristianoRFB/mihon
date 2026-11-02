package eu.kanade.tachiyomi.ui.reader.viewer.guided

/**
 * Validates model output before sorting or presenting speech bubbles.
 *
 * The ONNX model can produce overlapping or malformed boxes; keeping this logic
 * independent of Android and ONNX allows it to be tested on the JVM.
 */
internal object GuidedDetectionPostprocessor {
    private const val MIN_CONFIDENCE = 0.45f
    private const val DUPLICATE_IOU = 0.7f
    private const val REGION_PADDING = 0.12f

    fun process(regions: List<GuidedRegion>, direction: GuidedReadingDirection): List<GuidedRegion> {
        val accepted = mutableListOf<GuidedRegion>()
        regions.asSequence()
            .filter { it.isValid() }
            .sortedByDescending { it.confidence }
            .forEach { candidate ->
                if (accepted.none { it.bounds.intersectionOverUnion(candidate.bounds) >= DUPLICATE_IOU }) {
                    accepted += candidate
                }
            }

        // Deduplicate before expanding, otherwise neighboring distinct bubbles
        // can overlap enough to be incorrectly treated as a single detection.
        return GuidedRegionOrderer.order(
            accepted.map { it.copy(bounds = it.bounds.expanded(REGION_PADDING)) },
            direction,
        )
    }

    private fun GuidedRegion.isValid(): Boolean {
        if (!confidence.isFinite() || confidence < MIN_CONFIDENCE || confidence > 1f) return false
        val box = bounds
        if (!box.left.isFinite() || !box.top.isFinite() ||
            !box.right.isFinite() || !box.bottom.isFinite()
        ) return false

        return box.left >= 0f && box.top >= 0f &&
            box.right <= 1f && box.bottom <= 1f &&
            box.width > 0f && box.height > 0f
    }
}
