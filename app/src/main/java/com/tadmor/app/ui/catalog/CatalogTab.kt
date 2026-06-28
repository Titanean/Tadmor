package com.tadmor.app.ui.catalog

import com.tadmor.domain.model.Disposition

/**
 * Catalog sub-tab selection. CONFIRMED is always available; CANDIDATES and
 * FALSE_POSITIVES are revealed when [com.tadmor.domain.model.UserSettings.includeCandidates]
 * is true.
 */
enum class CatalogTab(val label: String, val disposition: Disposition) {
    CONFIRMED("Confirmed", Disposition.CONFIRMED),
    CANDIDATES("Candidates", Disposition.CANDIDATE),
    FALSE_POSITIVES("False positives", Disposition.FALSE_POSITIVE),
}
