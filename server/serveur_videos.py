import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
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
        resultats.append({
            "title": os.path.basename(chemin),
            "url": "/video/" + urllib.parse.quote(relatif),
            "thumb": "/thumb/" + urllib.parse.quote(relatif) + "?v=" + str(mtime),
            "size": taille,
        })
    return resultats


def chemin_miniature(relatif):
    nom = relatif.replace("/", "__") + ".jpg"
    return os.path.join(MINIATURES_DIR, nom)


def generer_miniature(video_abs, miniature_abs):
    if not FFMPEG:
        return False
    os.makedirs(MINIATURES_DIR, exist_ok=True)
    tmp = miniature_abs + ".part.jpg"
    creation = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    for cherche in (["-ss", "3"], ["-ss", "1"], []):
        cmd = [FFMPEG, "-y", "-loglevel", "error"] + cherche + [
            "-i", video_abs, "-frames:v", "1",
            "-vf", "scale=480:-2", "-q:v", "6", tmp,
        ]
        try:
            r = subprocess.run(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=45, creationflags=creation,
            )
        except Exception:
            r = None
        if r is not None and r.returncode == 0 and os.path.isfile(tmp):
            os.replace(tmp, miniature_abs)
            return True
    try:
        if os.path.exists(tmp):
            os.remove(tmp)
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
    print("Miniatures pre-generatees : %d" % nb)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

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

        self.send_error(404)

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
        print("Dossier vidéo introuvable : %s" % VIDEO_DIR)
        rep = input("Voulez-vous le créer ? [o/N] ").strip().lower()
        if rep == "o":
            os.makedirs(VIDEO_DIR)
        else:
            print('Relancez avec : python serveur_videos.py "CHEMIN_DU_DOSSIER"')
            sys.exit(1)

    serveur = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    nb_videos = len(lister_videos())
    print("=" * 60)
    print(" LocalTube - serveur de videos")
    print(" Dossier servi   : %s" % VIDEO_DIR)
    print(" Port            : %d" % PORT)
    print(" Videos trouvees : %d" % nb_videos)
    print(" Miniatures      : %s" %
          ("activees (ffmpeg)" if FFMPEG else "DESACTIVEES (ffmpeg introuvable)"))
    print("-" * 60)
    print(" Sur le telephone, entrez une de ces adresses :")
    for ip in adresses_locales():
        print("   http://%s:%d" % (ip, PORT))
    print("=" * 60)
    threading.Thread(target=pre_generer, daemon=True).start()
    print("Ctrl+C pour arreter.")
    try:
        serveur.serve_forever()
    except KeyboardInterrupt:
        print("\nArret.")


if __name__ == "__main__":
    principal()
