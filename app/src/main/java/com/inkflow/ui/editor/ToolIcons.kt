package com.inkflow.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Ícones próprios das ferramentas de escrita (traço de 1,7 no grid de 24). */
object ToolIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    /** Caneta-tinteiro: pena com fenda. */
    val Pen: ImageVector = icon("Pen") {
        moveTo(14.5f, 3.5f)
        lineTo(20.5f, 9.5f)
        lineTo(12f, 18f)
        lineTo(5f, 20f)
        lineTo(4f, 19f)
        lineTo(6f, 12f)
        close()
        moveTo(4.5f, 19.5f)
        lineTo(10f, 14f)
        moveTo(11.2f, 12.8f)
        lineTo(11.3f, 12.7f)
    }

    /** Lápis: corpo, ponta e anel. */
    val Pencil: ImageVector = icon("Pencil") {
        moveTo(16f, 3f)
        lineTo(21f, 8f)
        lineTo(9f, 20f)
        lineTo(3.5f, 20.5f)
        lineTo(4f, 15f)
        close()
        moveTo(14f, 5f)
        lineTo(19f, 10f)
        moveTo(4f, 15f)
        lineTo(9f, 20f)
    }

    /** Marca-texto: ponta chanfrada e faixa de cor. */
    val Highlighter: ImageVector = icon("Highlighter") {
        moveTo(9f, 14f)
        lineTo(16f, 4f)
        lineTo(20.5f, 8.5f)
        lineTo(11f, 16f)
        close()
        moveTo(9f, 14f)
        lineTo(6.5f, 17.5f)
        lineTo(8f, 19f)
        lineTo(11f, 16f)
        moveTo(3.5f, 21f)
        lineTo(20.5f, 21f)
    }

    /** Borracha: bloco inclinado com base. */
    val Eraser: ImageVector = icon("Eraser") {
        moveTo(8.5f, 20f)
        lineTo(3.8f, 15.3f)
        lineTo(14f, 5f)
        lineTo(20f, 11f)
        lineTo(11f, 20f)
        close()
        moveTo(8.8f, 10.2f)
        lineTo(14.8f, 16.2f)
        moveTo(11f, 20f)
        lineTo(20f, 20f)
    }

    /** Laço de seleção. */
    val Lasso: ImageVector = icon("Lasso") {
        moveTo(12f, 4f)
        curveTo(17f, 4f, 20.5f, 6.5f, 20.5f, 9.5f)
        curveTo(20.5f, 12.5f, 17f, 15f, 12f, 15f)
        curveTo(7f, 15f, 3.5f, 12.5f, 3.5f, 9.5f)
        curveTo(3.5f, 6.5f, 7f, 4f, 12f, 4f)
        close()
        moveTo(9f, 14.6f)
        curveTo(7.5f, 16.5f, 10.5f, 18f, 8.5f, 21f)
    }

    /** Apoio da mão: folha com faixa protegida embaixo. */
    val PalmGuard: ImageVector = icon("PalmGuard") {
        moveTo(5f, 3.5f)
        lineTo(19f, 3.5f)
        lineTo(19f, 20.5f)
        lineTo(5f, 20.5f)
        close()
        moveTo(5f, 13.5f)
        lineTo(19f, 13.5f)
        moveTo(8f, 17f)
        lineTo(10f, 15.5f)
        moveTo(11f, 18.5f)
        lineTo(14f, 15.5f)
        moveTo(15f, 18.5f)
        lineTo(17f, 16.5f)
        moveTo(8f, 7.5f)
        lineTo(15f, 7.5f)
        moveTo(8f, 10.5f)
        lineTo(13f, 10.5f)
    }
}
