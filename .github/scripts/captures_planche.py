#!/usr/bin/env python3
"""
Planche des captures d'écran automatiques de Champimap (lancé par la CI, job « captures »).

Usage : captures_planche.py <dossier_captures> <dossier_sortie> <sha> <journal_gradle>

- <dossier_captures> : app/build/captures (un sous-dossier par parcours, PNG + _manifeste.txt + _manquants.txt).
- <dossier_sortie>   : dossier publié (copie des PNG par parcours + index.html + rapport.txt).
- index.html : une section par parcours, 3 colonnes (clair / sombre / Psyché), nom de l'écran sous chaque image.
- rapport.txt : écrans manquants + erreurs de compilation / de test extraites du journal Gradle.
"""
import html
import os
import re
import shutil
import sys

THEMES = [("clair", "Clair"), ("sombre", "Sombre"), ("psyche", "Psyché")]
TITRES = {
    "1-premier-lancement": "1. Premier lancement",
    "2-sortie": "2. Une sortie",
    "3-menu": "3. Le menu",
}


def lire(chemin):
    """Contenu d'un fichier texte (vide s'il n'existe pas)."""
    try:
        with open(chemin, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def main():
    src, out, sha, journal = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    os.makedirs(out, exist_ok=True)
    parcours = sorted(d for d in os.listdir(src) if os.path.isdir(os.path.join(src, d))) if os.path.isdir(src) else []

    sections = []
    manquants = []
    total = 0
    for p in parcours:
        dossier = os.path.join(src, p)
        os.makedirs(os.path.join(out, p), exist_ok=True)
        for f in os.listdir(dossier):
            if f.endswith(".png") or f.startswith("_"):
                shutil.copy2(os.path.join(dossier, f), os.path.join(out, p, f))
        # Ordre et libellés : _manifeste.txt (nom<TAB>libellé), sinon ordre alphabétique des PNG.
        lignes = [l.split("\t", 1) for l in lire(os.path.join(dossier, "_manifeste.txt")).splitlines() if l.strip()]
        if not lignes:
            noms = sorted({re.sub(r"-(clair|sombre|psyche)\.png$", "", f) for f in os.listdir(dossier) if f.endswith(".png")})
            lignes = [[n, n] for n in noms]
        rangs = []
        for nom, *reste in lignes:
            libelle = reste[0] if reste else nom
            cellules = []
            for suffixe, _ in THEMES:
                fichier = f"{p}/{nom}-{suffixe}.png"
                if os.path.exists(os.path.join(out, fichier)):
                    total += 1
                    cellules.append(
                        f'<figure><a href="{html.escape(fichier)}"><img loading="lazy" src="{html.escape(fichier)}" '
                        f'alt="{html.escape(libelle)}"></a><figcaption>{html.escape(libelle)}</figcaption></figure>'
                    )
                else:
                    cellules.append('<figure class="vide"><div>non disponible</div></figure>')
            rangs.append('<div class="rang">' + "".join(cellules) + "</div>")
        for m in lire(os.path.join(dossier, "_manquants.txt")).splitlines():
            if m.strip():
                manquants.append(f"{p} / {m.strip()}")
        sections.append(
            f'<section><h2>{html.escape(TITRES.get(p, p))}</h2>'
            '<div class="rang tete">' + "".join(f"<div>{t}</div>" for _, t in THEMES) + "</div>"
            + "".join(rangs) + "</section>"
        )

    # Rapport : écrans manquants + erreurs du journal Gradle (compilation, tests).
    erreurs = [l for l in lire(journal).splitlines()
               if re.search(r"^e: |error:|FAILED|What went wrong|Exception|\[captures\]", l)]
    rapport = [f"Captures {sha} : {total} images."]
    rapport += ["", "Écrans manquants :"] + (manquants or ["(aucun)"])
    rapport += ["", "Journal (extrait) :"] + erreurs[:600]
    with open(os.path.join(out, "rapport.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(rapport) + "\n")

    liste_manquants = "".join(f"<li>{html.escape(m)}</li>" for m in manquants) or "<li>aucun</li>"
    page = f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Champimap — captures</title>
<style>
body {{ font-family: system-ui, sans-serif; margin: 0 auto; padding: 16px; max-width: 1200px; background: #F6F5F1; color: #1A1D1A; }}
h1 {{ color: #2F5D46; }} h2 {{ color: #2F5D46; border-bottom: 2px solid #E08A2E; padding-bottom: 4px; margin-top: 40px; }}
.rang {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 12px 0; }}
.tete div {{ font-weight: bold; text-align: center; }}
figure {{ margin: 0; background: #fff; border-radius: 12px; padding: 8px; box-shadow: 0 1px 3px #0002; }}
figure img {{ width: 100%; border-radius: 8px; display: block; }}
figcaption {{ font-size: 13px; text-align: center; margin-top: 6px; }}
.vide div {{ height: 120px; display: flex; align-items: center; justify-content: center; color: #999; }}
</style></head><body>
<h1>Champimap — captures d'écran</h1>
<p>Commit <code>{html.escape(sha)}</code> · {total} images · rendu Robolectric (carte MapLibre remplacée par une zone neutre).</p>
<details><summary>Écrans manquants ({len(manquants)})</summary><ul>{liste_manquants}</ul></details>
{''.join(sections)}
</body></html>
"""
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)
    print(f"Planche : {total} images, {len(manquants)} écrans manquants.")


if __name__ == "__main__":
    main()
