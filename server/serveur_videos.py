import hashlib
import html
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VIDEO_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(SCRIPT_DIR, "videos")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8000

MINIATURES_DIR = os.path.join(SCRIPT_DIR, "cache_miniatures")

EXTENSIONS_VIDEO = {
    ".mp4", ".m4v", ".mkv", ".webm", ".avi", ".mov",
    ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".3gp", ".ogv",
}

MIMES_SUP = {
    ".mkv": "video/x-matroska",
    ".m4v": "video/mp4",
    ".ogv": "video/ogg",
    ".ts": "video/mp2t",
}

CHUNK = 512 * 1024

LOG_FILE = os.path.join(SCRIPT_DIR, "serveur_log.txt")

ETAT_FILE = os.path.join(SCRIPT_DIR, "etat_parental.json")
VERROU_ETAT = threading.Lock()


def etat_defaut():
    return {
        "code": "123456",
        "bloque": False,
        "limite": 3600,
        "bonus": 0,
        "consomme": 0,
        "jour": time.strftime("%Y-%m-%d"),
        "vus": {},
        "demandes": [],
        "prochain_id": 1,
    }


def charger_etat():
    etat = etat_defaut()
    try:
        with open(ETAT_FILE, "r", encoding="utf-8") as f:
            etat.update(json.load(f))
    except (OSError, ValueError):
        pass
    return etat


def sauver_etat(etat):
    tmp = ETAT_FILE + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(etat, f, ensure_ascii=False)
        os.replace(tmp, ETAT_FILE)
    except OSError:
        pass


def nouveau_jour(etat):
    aujourd_hui = time.strftime("%Y-%m-%d")
    if etat.get("jour") != aujourd_hui:
        etat["jour"] = aujourd_hui
        etat["consomme"] = 0
        etat["bonus"] = 0


def calculer_bloquage(etat):
    if etat.get("bloque"):
        return True, "manuel", 0
    limite = int(etat.get("limite", 0))
    if limite > 0:
        restant = limite + int(etat.get("bonus", 0)) - int(etat.get("consomme", 0))
        if restant <= 0:
            return True, "temps", 0
        return False, "", restant
    return False, "", 999999


def jeton(code):
    return hashlib.sha256(("localtube-panel-" + code).encode("utf-8")).hexdigest()[:24]


def fmt_duree(sec):
    sec = int(sec)
    h = sec // 3600
    m = (sec % 3600) // 60
    if h > 0:
        return "%dh%02d" % (h, m)
    return "%d min" % m


def fmt_date(ts):
    try:
        return time.strftime("%d/%m/%Y %H:%M", time.localtime(int(ts)))
    except (ValueError, TypeError, OSError):
        return "?"


def ecrire(texte):
    if sys.stdout is not None:
        try:
            sys.stdout.write(texte + "\n")
            sys.stdout.flush()
            return
        except Exception:
            pass
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(time.strftime("[%Y-%m-%d %H:%M:%S] ") + texte + "\n")
    except OSError:
        pass


def trouver_ffmpeg():
    candidat = os.path.join(SCRIPT_DIR, "bin", "ffmpeg.exe")
    if os.path.isfile(candidat):
        return candidat
    return shutil.which("ffmpeg")


FFMPEG = trouver_ffmpeg()


def iterer_videos():
    racine_abs = os.path.abspath(VIDEO_DIR)
    for racine, dossiers, fichiers in os.walk(racine_abs):
        dossiers[:] = sorted(d for d in dossiers if not d.startswith("."))
        for nom in sorted(fichiers):
            if nom.startswith("."):
                continue
            ext = os.path.splitext(nom)[1].lower()
            if ext not in EXTENSIONS_VIDEO:
                continue
            chemin = os.path.join(racine, nom)
            relatif = os.path.relpath(chemin, racine_abs).replace("\\", "/")
            yield chemin, relatif


def lister_videos():
    resultats = []
    for chemin, relatif in iterer_videos():
        try:
            taille = os.path.getsize(chemin)
            mtime = int(os.path.getmtime(chemin))
        except OSError:
            taille = 0
            mtime = 0
        version = mtime
        try:
            mini = chemin_miniature(relatif)
            if os.path.isfile(mini):
                version = int(os.path.getmtime(mini))
        except OSError:
            pass
        resultats.append({
            "title": os.path.basename(chemin),
            "url": "/video/" + urllib.parse.quote(relatif),
            "thumb": "/thumb/" + urllib.parse.quote(relatif) + "?v=" + str(version),
            "size": taille,
        })
    return resultats


def chemin_miniature(relatif):
    nom = relatif.replace("/", "__") + ".jpg"
    return os.path.join(MINIATURES_DIR, nom)


def options_creation():
    return subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def duree_video(video_abs):
    try:
        cmd = [FFMPEG, "-hide_banner", "-i", video_abs]
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=20, creationflags=options_creation())
        m = re.search(r"Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)",
                      (r.stderr or "") + (r.stdout or ""))
        if not m:
            return 0.0
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    except Exception:
        return 0.0


def luminance_moyenne(image_jpg, creation):
    try:
        cmd = [FFMPEG, "-hide_banner", "-nostats", "-i", image_jpg,
               "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG",
               "-f", "null", "-"]
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=20, creationflags=creation)
        m = re.search(r"YAVG=([\d.]+)", (r.stderr or "") + (r.stdout or ""))
        return float(m.group(1)) if m else -1.0
    except Exception:
        return -1.0


def generer_miniature(video_abs, miniature_abs):
    if not FFMPEG:
        return False
    os.makedirs(MINIATURES_DIR, exist_ok=True)
    creation = options_creation()
    tmp = "%s.part%d.jpg" % (miniature_abs, threading.get_ident())
    sauvetage = None

    duree = duree_video(video_abs)
    essais = []
    if duree > 20:
        essais.append(min(int(duree * 0.10), 600))
        essais.append(min(int(duree * 0.35), 900))
        essais.append(min(int(duree * 0.65), 1200))
    elif duree > 2:
        essais.append(max(1, int(duree * 0.5)))
    else:
        essais.append(5)
    essais.extend([3, 1, 0])

    deja_vus = set()
    for seconde in essais:
        if seconde < 0 or seconde in deja_vus:
            continue
        deja_vus.add(seconde)
        cherche = ["-ss", str(seconde)] if seconde > 0 else []
        cmd = ([FFMPEG, "-y", "-loglevel", "error"] + cherche +
               ["-i", video_abs, "-frames:v", "1",
                "-vf", "scale=480:-2", "-q:v", "6", tmp])
        try:
            r = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL,
                               timeout=45, creationflags=creation)
        except Exception:
            r = None
        if r is None or r.returncode != 0 or not os.path.isfile(tmp):
            continue

        lum = luminance_moyenne(tmp, creation)
        if lum < 0 or lum >= 18:
            os.replace(tmp, miniature_abs)
            return True
        if sauvetage is None:
            sauvetage = tmp + ".secours"
            try:
                os.replace(tmp, sauvetage)
            except OSError:
                sauvetage = None

    if sauvetage is not None and os.path.isfile(sauvetage):
        os.replace(sauvetage, miniature_abs)
        return True

    for residu in (tmp, sauvetage):
        try:
            if residu and os.path.exists(residu):
                os.remove(residu)
        except OSError:
            pass
    return False


def assurer_miniature(video_abs, miniature_abs):
    try:
        if os.path.isfile(miniature_abs) and \
                os.path.getmtime(miniature_abs) >= os.path.getmtime(video_abs):
            return True
    except OSError:
        pass
    return generer_miniature(video_abs, miniature_abs)


def pre_generer():
    if not FFMPEG:
        return
    nb = 0
    for video_abs, relatif in iterer_videos():
        if assurer_miniature(video_abs, chemin_miniature(relatif)):
            nb += 1
    ecrire("Miniatures pre-generatees : %d" % nb)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        ecrire("%s - %s" % (self.address_string(), fmt % args))

    def do_GET(self):
        self.repondre()

    def do_HEAD(self):
        self.repondre(head=True)

    def repondre(self, head=False):
        chemin = urllib.parse.urlparse(self.path).path

        if chemin in ("/", "/api/videos"):
            corps = json.dumps({"videos": lister_videos()}, ensure_ascii=False).encode("utf-8")
            self.envoyer_entetes(200, "application/json; charset=utf-8", len(corps))
            if not head:
                self.wfile.write(corps)
            return

        if chemin.startswith("/video/"):
            self.servir_fichier(chemin[len("/video/"):], head=head)
            return

        if chemin.startswith("/thumb/"):
            self.servir_miniature(chemin[len("/thumb/"):], head=head)
            return

        if chemin == "/api/parental":
            self.api_parental_etat()
            return

        if chemin == "/panel":
            self.panel_page()
            return

        self.send_error(404)

    def do_POST(self):
        chemin = urllib.parse.urlparse(self.path).path
        if chemin == "/api/parental/tick":
            self.api_parental_tick()
        elif chemin == "/api/parental/demande":
            self.api_parental_demande()
        elif chemin == "/panel/login":
            self.panel_login()
        elif chemin == "/panel/action":
            self.panel_action()
        else:
            self.send_error(404)

    def lire_params(self):
        try:
            longueur = int(self.headers.get("Content-Length", 0) or 0)
        except ValueError:
            longueur = 0
        corps = self.rfile.read(longueur).decode("utf-8", "replace") if longueur else ""
        return urllib.parse.parse_qs(corps)

    def envoyer_json(self, obj):
        corps = json.dumps(obj).encode("utf-8")
        self.envoyer_entetes(200, "application/json; charset=utf-8", len(corps))
        self.wfile.write(corps)

    def rediriger(self, vers, cookie=None):
        self.send_response(303)
        self.send_header("Location", vers)
        self.send_header("Content-Length", "0")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()

    def etat_public(self, etat, raison="", restant=0):
        bloque, raison_calc, restant_calc = calculer_bloquage(etat)
        return {
            "bloque": bloque,
            "raison": raison_calc or raison,
            "restant": restant_calc,
            "limite": int(etat.get("limite", 0)),
            "consomme": int(etat.get("consomme", 0)),
        }

    def api_parental_etat(self):
        with VERROU_ETAT:
            etat = charger_etat()
            nouveau_jour(etat)
            sauver_etat(etat)
            self.envoyer_json(self.etat_public(etat))

    def api_parental_tick(self):
        p = self.lire_params()
        try:
            sec = min(max(int(p.get("sec", ["0"])[0] or 0), 0), 120)
        except ValueError:
            sec = 0
        titre = (p.get("video", [""])[0] or "")[:200]
        with VERROU_ETAT:
            etat = charger_etat()
            nouveau_jour(etat)
            bloque, _, _ = calculer_bloquage(etat)
            if not bloque and sec > 0:
                etat["consomme"] += sec
                if titre:
                    vu = etat["vus"].setdefault(titre, {"secondes": 0, "dernier": 0})
                    vu["secondes"] += sec
                    vu["dernier"] = int(time.time())
                sauver_etat(etat)
            self.envoyer_json(self.etat_public(etat))

    def api_parental_demande(self):
        p = self.lire_params()
        texte = (p.get("texte", [""])[0] or "").strip()[:300]
        with VERROU_ETAT:
            etat = charger_etat()
            demande = {
                "id": int(etat.get("prochain_id", 1)),
                "date": time.strftime("%d/%m %H:%M"),
                "ts": int(time.time()),
                "texte": texte,
                "statut": "en_attente",
            }
            etat["prochain_id"] = demande["id"] + 1
            etat["demandes"].append(demande)
            etat["demandes"] = etat["demandes"][-50:]
            sauver_etat(etat)
        self.envoyer_json({"ok": True})

    def panel_connecte(self, etat):
        m = re.search(r"locatube_panel=([0-9a-f]+)", self.headers.get("Cookie", ""))
        return bool(m and m.group(1) == jeton(str(etat.get("code", ""))))

    def panel_page(self):
        with VERROU_ETAT:
            etat = charger_etat()
        if not self.panel_connecte(etat):
            self.html_reponse(page_login_html())
            return
        self.html_reponse(construire_tableau(etat))

    def html_reponse(self, contenu):
        corps = contenu.encode("utf-8")
        self.envoyer_entetes(200, "text/html; charset=utf-8", len(corps))
        self.wfile.write(corps)

    def panel_login(self):
        p = self.lire_params()
        code_saisi = (p.get("code", [""])[0] or "").strip()
        with VERROU_ETAT:
            etat = charger_etat()
        if code_saisi == str(etat.get("code", "")):
            self.rediriger("/panel", "locatube_panel=%s; Path=/; HttpOnly" % jeton(code_saisi))
        else:
            self.html_reponse(page_login_html("<p class='erreur'>Code incorrect.</p>"))

    def panel_action(self):
        p = self.lire_params()
        action = p.get("action", [""])[0]
        with VERROU_ETAT:
            etat = charger_etat()
            if not self.panel_connecte(etat):
                self.rediriger("/panel")
                return
            nouveau_jour(etat)
            if action == "blocage":
                etat["bloque"] = p.get("etat", [""])[0] == "on"
            elif action == "limite":
                try:
                    minutes = max(0, int(p.get("minutes", ["0"])[0]))
                except ValueError:
                    minutes = 0
                etat["limite"] = minutes * 60
            elif action == "reset_jour":
                etat["consomme"] = 0
                etat["bonus"] = 0
            elif action == "vider_histo":
                etat["vus"] = {}
            elif action in ("demande_ok", "demande_non"):
                try:
                    ident = int(p.get("id", ["0"])[0])
                except ValueError:
                    ident = 0
                for d in etat.get("demandes", []):
                    if d.get("id") == ident and d.get("statut") == "en_attente":
                        if action == "demande_ok":
                            d["statut"] = "acceptee"
                            try:
                                bonus_min = max(1, min(600, int(p.get("minutes", ["15"])[0])))
                            except ValueError:
                                bonus_min = 15
                            etat["bonus"] = int(etat.get("bonus", 0)) + bonus_min * 60
                            d["reponse"] = "+%d min" % bonus_min
                        else:
                            d["statut"] = "refusee"
                        break
            sauver_etat(etat)
        self.rediriger("/panel")

    def envoyer_entetes(self, code, type_contenu, longueur, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", type_contenu)
        self.send_header("Content-Length", str(longueur))
        self.send_header("Access-Control-Allow-Origin", "*")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()

    def refuser_plage(self, taille):
        self.send_response(416)
        self.send_header("Content-Length", "0")
        self.send_header("Content-Range", "bytes */%d" % taille)
        self.end_headers()

    def resoudre_chemin(self, chemin_relatif):
        racine_abs = os.path.abspath(VIDEO_DIR)
        chemin = os.path.normpath(os.path.join(racine_abs, urllib.parse.unquote(chemin_relatif)))
        chemin_abs = os.path.abspath(chemin)
        if chemin_abs != racine_abs and not chemin_abs.startswith(racine_abs + os.sep):
            return None
        return chemin_abs

    def servir_fichier(self, chemin_relatif, head=False):
        chemin_abs = self.resoudre_chemin(chemin_relatif)
        if chemin_abs is None:
            self.send_error(403)
            return
        if not os.path.isfile(chemin_abs):
            self.send_error(404)
            return

        taille = os.path.getsize(chemin_abs)
        ext = os.path.splitext(chemin_abs)[1].lower()
        type_contenu = MIMES_SUP.get(ext) or mimetypes.guess_type(chemin_abs)[0] \
            or "application/octet-stream"

        debut, fin = 0, taille - 1
        code = 200
        entetes_extra = {"Accept-Ranges": "bytes"}

        plage = self.headers.get("Range")
        if plage:
            m = re.match(r"bytes=(\d*)-(\d*)$", plage.strip())
            if m:
                s, e = m.group(1), m.group(2)
                if s == "" and e != "":
                    nb = int(e)
                    if nb <= 0:
                        self.refuser_plage(taille)
                        return
                    debut = max(0, taille - nb)
                else:
                    debut = int(s)
                    if e != "":
                        fin = min(int(e), taille - 1)
                if debut > fin or debut >= taille:
                    self.refuser_plage(taille)
                    return
                code = 206
                entetes_extra["Content-Range"] = "bytes %d-%d/%d" % (debut, fin, taille)

        longueur = fin - debut + 1
        self.log_message('"%s" -> %s octets %d-%d', chemin_relatif, code, debut, fin)
        self.envoyer_entetes(code, type_contenu, longueur, entetes_extra)

        if head:
            return

        try:
            with open(chemin_abs, "rb") as f:
                f.seek(debut)
                restant = longueur
                while restant > 0:
                    morceau = f.read(min(CHUNK, restant))
                    if not morceau:
                        break
                    self.wfile.write(morceau)
                    restant -= len(morceau)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def servir_miniature(self, chemin_relatif, head=False):
        video_abs = self.resoudre_chemin(chemin_relatif)
        if video_abs is None:
            self.send_error(403)
            return
        if not os.path.isfile(video_abs):
            self.send_error(404)
            return

        relatif = urllib.parse.unquote(chemin_relatif).replace("\\", "/")
        mini_abs = chemin_miniature(relatif)

        if not assurer_miniature(video_abs, mini_abs):
            self.send_error(404)
            return

        try:
            with open(mini_abs, "rb") as f:
                donnees = f.read()
        except OSError:
            self.send_error(404)
            return

        self.log_message('miniature "%s" -> %d octets', relatif, len(donnees))
        self.envoyer_entetes(200, "image/jpeg", len(donnees),
                             {"Cache-Control": "max-age=86400"})
        if not head:
            self.wfile.write(donnees)


CSS_PANEL = """
body{background:#121212;color:#eee;font-family:Segoe UI,Arial,sans-serif;margin:0;padding:20px}
.cont{max-width:760px;margin:0 auto}
h1{font-size:22px;color:#ff6b6b} h2{font-size:16px;border-bottom:1px solid #333;padding-bottom:6px}
.carte{background:#1e1e1e;border-radius:10px;padding:16px;margin-bottom:16px}
.ligne{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:8px 0}
.badge{padding:4px 12px;border-radius:14px;font-weight:bold;font-size:13px}
.vert{background:#1b5e20}.rouge{background:#b71c1c}
button,input[type=number],input[type=password]{background:#2a2a2a;color:#eee;border:1px solid #444;
 border-radius:6px;padding:8px 14px;font-size:14px}
button{cursor:pointer} button:hover{background:#3a3a3a}
button.rouge{background:#b71c1c;border-color:#b71c1c} button.vert{background:#1b5e20;border-color:#1b5e20}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{padding:7px 6px;text-align:left;border-bottom:1px solid #2a2a2a}
th{color:#999;font-weight:normal}
.erreur{color:#ff8a80}.attente{color:#ffd54f}.okc{color:#81c784}.refus{color:#e57373}
.temps{font-size:26px;font-weight:bold;margin:6px 0}
.demande{border:1px solid #333;border-radius:8px;padding:10px;margin:8px 0}
.petit{color:#999;font-size:12px}
"""

PAGE_LOGIN = """<!doctype html><html lang=fr><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>LocalTube - Panel</title><style>__CSS__</style></head><body><div class=cont>
<h1>LocalTube - Panel parental</h1>
<div class=carte><form method=post action=/panel/login>
<label>Code d'acces :</label><br><br>
<input type=password name=code autofocus autocomplete=off>
<button type=submit>Entrer</button>
__ERREUR__</form></div></div></body></html>"""


def page_login_html(erreur=""):
    return PAGE_LOGIN.replace("__CSS__", CSS_PANEL).replace("__ERREUR__", erreur)


def construire_tableau(etat):
    bloque, raison, restant = calculer_bloquage(etat)
    if bloque:
        badge = "<span class='badge rouge'>BLOQUE (%s)</span>" % (
            "manuel" if raison == "manuel" else "temps ecoule")
    else:
        texte_restant = "illimite" if int(etat.get("limite", 0)) == 0 \
            else fmt_duree(restant) + " restantes"
        badge = "<span class='badge vert'>AUTORISE</span> <span class='temps'>%s</span>" % texte_restant

    bouton_blocage = ("<form style=display:inline method=post action=/panel/action>"
                      "<input type=hidden name=action value=blocage>"
                      "<input type=hidden name=etat value=%s>"
                      "<button class='%s'>%s</button></form>") % (
        "off" if etat.get("bloque") else "on",
        "vert" if etat.get("bloque") else "rouge",
        "Debloquer" if etat.get("bloque") else "Bloquer tout")

    demandes_html = ""
    liste = sorted(etat.get("demandes", []), key=lambda d: d.get("id", 0), reverse=True)
    for d in liste:
        statut = d.get("statut")
        if statut == "en_attente":
            suite = ("<form style=display:inline method=post action=/panel/action>"
                     "<input type=hidden name=action value=demande_ok>"
                     "<input type=hidden name=id value=%d>"
                     "<input type=number name=minutes value=15 min=1 max=600 style=width:70px> min "
                     "<button class=vert>Accepter</button></form> "
                     "<form style=display:inline method=post action=/panel/action>"
                     "<input type=hidden name=action value=demande_non>"
                     "<input type=hidden name=id value=%d>"
                     "<button class=rouge>Refuser</button></form>") % (d["id"], d["id"])
            classe = "attente"
            libelle = "EN ATTENTE"
        elif statut == "acceptee":
            suite = ""
            classe = "okc"
            libelle = "ACCEPTEE %s" % d.get("reponse", "")
        else:
            suite = ""
            classe = "refus"
            libelle = "REFUSEE"
        textes = html.escape(d.get("texte", ""))
        demandes_html += ("<div class=demande><span class='%s'>[%s]</span> "
                          "<span class=petit>%s</span><br>%s<br>%s</div>") % (
            classe, libelle, html.escape(str(d.get("date", ""))), textes, suite)
    if not liste:
        demandes_html = "<p class=petit>Aucune demande.</p>"

    lignes_histo = ""
    vus = sorted(etat.get("vus", {}).items(), key=lambda kv: kv[1].get("dernier", 0), reverse=True)
    for titre, infos in vus:
        lignes_histo += ("<tr><td>%s</td><td>%s</td><td>%s</td></tr>") % (
            html.escape(titre), fmt_duree(infos.get("secondes", 0)),
            fmt_date(infos.get("dernier", 0)))
    if not lignes_histo:
        lignes_histo = "<tr><td colspan=3 class=petit>Aucune video vue pour le moment.</td></tr>"

    return """<!doctype html><html lang=fr><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>LocalTube - Panel</title><style>%s</style></head><body><div class=cont>
<h1>LocalTube - Panel parental</h1>

<div class=carte><h2>Etat</h2>%s
<div class=ligne>
%s
<form style=display:inline method=post action=/panel/action>
<input type=hidden name=action value=limite>
Limite journaliere : <input type=number name=minutes value=%d min=0 max=1440 style=width:90px> min
<button>Enregistrer</button></form>
<form style=display:inline method=post action=/panel/action>
<input type=hidden name=action value=reset_jour><button>Reinitialiser le jour</button></form>
</div>
<p class=petit>Mettre 0 = illimite. Consomme aujourd'hui : %s</p></div>

<div class=carte><h2>Demandes de temps</h2>%s</div>

<div class=carte><h2>Historique des videos vues</h2>
<table><tr><th>Video</th><th>Temps total</th><th>Derniere fois</th></tr>
%s</table>
<form method=post action=/panel/action style=margin-top:10px>
<input type=hidden name=action value=vider_histo>
<button class=rouge>Vider l'historique</button></form></div>

<p class=petit>LocalTube - panel reserve aux parents.</p>
</div></body></html>""" % (
        CSS_PANEL, badge, bouton_blocage,
        int(etat.get("limite", 0)) // 60,
        fmt_duree(etat.get("consomme", 0)),
        demandes_html, lignes_histo)


def adresses_locales():
    adresses = set()
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        for info in infos:
            ip = info[4][0]
            if not ip.startswith("127."):
                adresses.add(ip)
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        adresses.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(adresses)


def principal():
    global VIDEO_DIR
    VIDEO_DIR = os.path.abspath(VIDEO_DIR)

    if not os.path.isdir(VIDEO_DIR):
        if sys.stdin is not None:
            print("Dossier vidéo introuvable : %s" % VIDEO_DIR)
            rep = input("Voulez-vous le créer ? [o/N] ").strip().lower()
            if rep == "o":
                os.makedirs(VIDEO_DIR)
            else:
                print('Relancez avec : python serveur_videos.py "CHEMIN_DU_DOSSIER"')
                sys.exit(1)
        else:
            os.makedirs(VIDEO_DIR)
            ecrire("Dossier cree : %s" % VIDEO_DIR)

    try:
        serveur = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError as err:
        ecrire("Impossible de demarrer sur le port %d : %s" % (PORT, err))
        sys.exit(1)

    nb_videos = len(lister_videos())
    ecrire("=" * 60)
    ecrire(" LocalTube - serveur de videos")
    ecrire(" Dossier servi   : %s" % VIDEO_DIR)
    ecrire(" Port            : %d" % PORT)
    ecrire(" Videos trouvees : %d" % nb_videos)
    ecrire(" Miniatures      : %s" %
          ("activees (ffmpeg)" if FFMPEG else "DESACTIVEES (ffmpeg introuvable)"))
    ecrire("-" * 60)
    ecrire(" Sur le telephone, entrez une de ces adresses :")
    for ip in adresses_locales():
        ecrire("   http://%s:%d" % (ip, PORT))
        ecrire("   Panel parental : http://%s:%d/panel" % (ip, PORT))
    ecrire("=" * 60)
    threading.Thread(target=pre_generer, daemon=True).start()
    if sys.stdin is None:
        ecrire("Mode arriere-plan : fermez via le gestionnaire des taches")
        ecrire("(pythonw.exe) ou avec arrester_serveur.bat.")
    else:
        print("Ctrl+C pour arreter.")
    try:
        serveur.serve_forever()
    except KeyboardInterrupt:
        print("\nArret.")


if __name__ == "__main__":
    principal()
