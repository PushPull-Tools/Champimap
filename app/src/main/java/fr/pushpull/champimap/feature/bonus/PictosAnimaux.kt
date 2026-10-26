package fr.pushpull.champimap.feature.bonus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/*
 * Les 8 animaux débloqués par l'easter egg. Repère 100 × 100, sol vers y = 92.
 * Silhouettes de profil (tête à droite), formes simples et yeux bien visibles.
 */

private val OEIL = Color(0xFF1A1A1A)

/** Écureuil roux assis, grande queue en panache. */
internal fun DrawScope.drawEcureuil() {
    val roux = Color(0xFFC0602A)
    val queue = path {
        moveTo(46f, 86f)
        cubicTo(18f, 90f, 10f, 60f, 22f, 40f)
        cubicTo(30f, 26f, 44f, 22f, 42f, 36f)
        cubicTo(30f, 46f, 34f, 70f, 50f, 78f)
        close()
    }
    drawPath(queue, Color(0xFFD9773A))
    oval(roux, 56f, 70f, 15f, 19f) // corps
    oval(Color(0xFFF2D7B0), 61f, 72f, 7f, 12f) // ventre
    disc(roux, 64f, 46f, 11f) // tête
    poly(roux, 58f, 38f, 60f, 26f, 65f, 37f) // oreille
    disc(OEIL, 68f, 44f, 2f)
    disc(Color(0xFF3A2416), 75f, 48f, 1.6f) // truffe
    oval(Color(0xFFA34E20), 66f, 88f, 8f, 3f) // pattes
    oval(Color(0xFF7A4A1E), 70f, 64f, 4f, 3f) // noisette tenue
}

/** Hérisson : dos de piquants, museau pointu. */
internal fun DrawScope.drawHerisson() {
    val piquants = path {
        moveTo(18f, 84f)
        lineTo(16f, 72f); lineTo(22f, 74f); lineTo(20f, 60f); lineTo(28f, 64f)
        lineTo(30f, 50f); lineTo(38f, 56f); lineTo(44f, 44f); lineTo(50f, 54f)
        lineTo(58f, 46f); lineTo(62f, 56f); lineTo(70f, 52f); lineTo(70f, 64f)
        lineTo(76f, 66f); lineTo(72f, 84f)
        close()
    }
    drawPath(piquants, Color(0xFF5A4636))
    oval(Color(0xFF7A6250), 44f, 74f, 22f, 12f)
    // Tête et museau
    val tete = path {
        moveTo(66f, 66f)
        cubicTo(74f, 64f, 82f, 72f, 90f, 80f)
        lineTo(86f, 84f)
        lineTo(66f, 86f)
        close()
    }
    drawPath(tete, Color(0xFFD2B48C))
    disc(OEIL, 76f, 73f, 1.8f)
    disc(OEIL, 90f, 80f, 2.2f) // truffe
    oval(Color(0xFF4A3828), 34f, 88f, 4f, 2f)
    oval(Color(0xFF4A3828), 66f, 88f, 4f, 2f)
}

/** Rouge-gorge : boule brune à plastron orange, sur une petite branche. */
internal fun DrawScope.drawRougeGorge() {
    stroke(Color(0xFF6B4A2E), 14f, 84f, 86f, 80f, 3f) // branche
    poly(Color(0xFF6E5A44), 22f, 62f, 8f, 70f, 12f, 76f, 30f, 68f) // queue
    oval(Color(0xFF7A644C), 46f, 60f, 24f, 20f) // corps
    oval(Color(0xFFE2622A), 58f, 58f, 13f, 14f) // plastron
    oval(Color(0xFFEDE6DA), 52f, 74f, 12f, 5f) // ventre
    disc(Color(0xFF7A644C), 60f, 40f, 12f) // tête
    oval(Color(0xFFE2622A), 64f, 46f, 8f, 7f) // gorge
    disc(OEIL, 64f, 37f, 2f)
    poly(Color(0xFF2A2A2A), 71f, 39f, 78f, 41f, 71f, 43f) // bec
    stroke(Color(0xFF8A6A4A), 44f, 79f, 42f, 82f, 1.5f) // pattes
    stroke(Color(0xFF8A6A4A), 52f, 79f, 52f, 81f, 1.5f)
}

/** Chevreuil debout, petits bois, fesses blanches. */
internal fun DrawScope.drawChevreuil() {
    val fauve = Color(0xFFA8683A)
    // Pattes
    stroke(fauve, 34f, 62f, 32f, 90f, 4f)
    stroke(fauve, 40f, 62f, 40f, 90f, 4f)
    stroke(fauve, 62f, 62f, 62f, 90f, 4f)
    stroke(fauve, 68f, 62f, 70f, 90f, 4f)
    oval(fauve, 51f, 56f, 22f, 11f) // corps
    oval(Color(0xFFF2ECE0), 30f, 54f, 5f, 6f) // miroir blanc
    poly(fauve, 64f, 52f, 72f, 50f, 78f, 30f, 72f, 28f) // cou
    oval(fauve, 78f, 29f, 9f, 6f) // tête
    disc(OEIL, 79f, 27f, 1.6f)
    disc(OEIL, 86f, 31f, 1.6f) // truffe
    oval(Color(0xFF8A522A), 72f, 22f, 3f, 5f) // oreille
    // Petits bois
    val bois = Color(0xFF5A4030)
    stroke(bois, 76f, 23f, 76f, 12f, 1.8f)
    stroke(bois, 76f, 16f, 72f, 13f, 1.6f)
    stroke(bois, 79f, 23f, 81f, 12f, 1.8f)
}

/** Renard : corps orange, queue touffue à bout blanc, museau pointu. */
internal fun DrawScope.drawRenard() {
    val orange = Color(0xFFE06A22)
    val queue = path {
        moveTo(30f, 66f)
        cubicTo(16f, 70f, 8f, 82f, 14f, 88f)
        cubicTo(24f, 90f, 34f, 80f, 38f, 72f)
        close()
    }
    drawPath(queue, orange)
    oval(Color.White, 15f, 86f, 4f, 3f) // bout de queue
    stroke(Color(0xFF2A1E18), 40f, 70f, 38f, 90f, 3.5f) // pattes noires
    stroke(Color(0xFF2A1E18), 62f, 70f, 62f, 90f, 3.5f)
    oval(orange, 50f, 66f, 18f, 10f) // corps
    // Tête triangulaire
    poly(orange, 62f, 42f, 70f, 64f, 90f, 54f)
    poly(Color.White, 72f, 56f, 90f, 54f, 76f, 62f) // joue blanche
    poly(orange, 62f, 42f, 64f, 30f, 70f, 44f) // oreille
    poly(Color(0xFF2A1E18), 63f, 36f, 64f, 30f, 66.5f, 38f)
    disc(OEIL, 73f, 49f, 1.8f)
    disc(OEIL, 90f, 54f, 2f)
}

/** Sanglier : masse sombre, groin, défense blanche. */
internal fun DrawScope.drawSanglier() {
    val brun = Color(0xFF3E2E24)
    stroke(brun, 32f, 70f, 32f, 90f, 5f)
    stroke(brun, 44f, 70f, 44f, 90f, 5f)
    stroke(brun, 58f, 70f, 58f, 90f, 5f)
    stroke(brun, 68f, 70f, 70f, 90f, 5f)
    val corps = path {
        moveTo(20f, 70f)
        cubicTo(16f, 52f, 34f, 40f, 54f, 42f)
        cubicTo(70f, 44f, 78f, 50f, 88f, 62f)
        lineTo(88f, 70f)
        cubicTo(70f, 78f, 36f, 80f, 20f, 70f)
        close()
    }
    drawPath(corps, Color(0xFF54402F))
    // Crinière
    stroke(Color(0xFF2E221A), 34f, 45f, 62f, 44f, 3f)
    oval(Color(0xFF8A6A58), 88f, 66f, 3f, 4.5f) // groin
    poly(Color(0xFFF2ECE0), 82f, 70f, 86f, 60f, 84f, 71f) // défense
    disc(OEIL, 74f, 55f, 1.8f)
    poly(Color(0xFF3E2E24), 66f, 48f, 68f, 38f, 72f, 48f) // oreille
}

/** Chouette hulotte : corps rond, grands yeux en disque facial. */
internal fun DrawScope.drawChouette() {
    stroke(Color(0xFF6B4A2E), 18f, 88f, 82f, 88f, 4f) // branche
    oval(Color(0xFF8A6A48), 50f, 60f, 24f, 28f) // corps
    oval(Color(0xFFC9AE88), 50f, 68f, 14f, 17f) // ventre
    // Mouchetures du ventre
    val mouche = Color(0xFF8A6A48)
    oval(mouche, 45f, 64f, 2f, 1.2f)
    oval(mouche, 55f, 64f, 2f, 1.2f)
    oval(mouche, 50f, 72f, 2f, 1.2f)
    oval(mouche, 44f, 78f, 2f, 1.2f)
    oval(mouche, 56f, 78f, 2f, 1.2f)
    // Disque facial et yeux
    disc(Color(0xFFD9C29C), 41f, 42f, 10f)
    disc(Color(0xFFD9C29C), 59f, 42f, 10f)
    disc(OEIL, 41f, 42f, 5f)
    disc(OEIL, 59f, 42f, 5f)
    disc(Color.White, 42.5f, 40.5f, 1.5f)
    disc(Color.White, 60.5f, 40.5f, 1.5f)
    poly(Color(0xFFE0A030), 47f, 48f, 53f, 48f, 50f, 54f) // bec
    oval(Color(0xFFE0A030), 44f, 88f, 4f, 2f) // serres
    oval(Color(0xFFE0A030), 56f, 88f, 4f, 2f)
}

/** Lapin de garenne assis, longues oreilles, queue blanche. */
internal fun DrawScope.drawLapin() {
    val gris = Color(0xFF9A8670)
    disc(Color.White, 28f, 76f, 6f) // queue
    oval(gris, 46f, 74f, 20f, 16f) // corps
    oval(gris, 58f, 88f, 10f, 4f) // patte arrière
    disc(gris, 66f, 52f, 12f) // tête
    // Oreilles
    oval(gris, 58f, 30f, 4.5f, 14f)
    oval(gris, 66f, 28f, 4.5f, 14f)
    oval(Color(0xFFE8B8B0), 66f, 28f, 2f, 10f)
    disc(OEIL, 71f, 50f, 2f)
    disc(Color(0xFFD08A8A), 78f, 55f, 1.6f) // truffe
}
