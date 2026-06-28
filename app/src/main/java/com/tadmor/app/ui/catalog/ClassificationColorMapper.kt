package com.tadmor.app.ui.catalog

import com.tadmor.app.ui.theme.ClassificationColor
import com.tadmor.app.ui.theme.ExoColors
import com.tadmor.domain.classification.CompositionClass

/**
 * Maps domain CompositionClass enums to presentation-layer ClassificationColor tokens.
 */
fun CompositionClass.toColor(): ClassificationColor = when (this) {
    CompositionClass.TERRA -> ExoColors.compositionTerra
    CompositionClass.NEPTUNE -> ExoColors.compositionNeptune
    CompositionClass.JUPITER -> ExoColors.compositionJupiter
}
