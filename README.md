# LocalTube

Une mini "YouTube" maison : une app Android qui liste, recherche et lit les
vidéos stockées dans **un dossier de ton PC**, en streaming par Wi-Fi.

```
LocalTube/
├── lancer_serveur.bat        ← à double-cliquer sur le PC (Windows)
├── server/serveur_videos.py  ← le serveur Python (aucune dépendance)
└── app/, gradle/...          ← le projet Android Studio (application)
```

---

## 1. Sur le PC : lancer le serveur

1. Double-clique sur `lancer_serveur.bat` (il sert `%USERPROFILE%\Videos`
   sur le port `8000`). Pour servir un autre dossier :
   ```
   python serveur_videos.py "D:\Mes Films" 8000
   ```
2. Au premier lancement, Windows demande l'autorisation du pare-feu →
   coche **Réseaux privés** et valide.
3. Le script affiche les adresses à utiliser, par ex. :
   `http://192.168.1.50:8000`

Formats reconnus : mp4, m4v, mkv, webm, avi, mov, wmv, flv, mpg, mpeg,
ts, 3gp, ogv (y compris dans des sous-dossiers).

**Miniatures** : le serveur génère automatiquement les vignettes des
vidéos grâce à ffmpeg (inclus dans `server\bin`). Elles sont mises en
cache dans `server\cache_miniatures` — tu peux supprimer ce dossier sans
risque, elles se régénéreront.

## 2. Sur ton ordinateur : construire l'app

1. Installe [Android Studio](https://developer.android.com/studio) (récent,
   Hedgehog 2023.1 ou plus) s'il n'est pas déjà installé.
2. `File → Open` puis choisis le dossier `LocalTube`.
3. Laisse le sync Gradle se terminer.
4. Menu `Build → Build App Bundle(s)/APK(s) → Build APK(s)`.
5. L'APK se trouve dans
   `app/build/outputs/apk/debug/app-debug.apk`.
6. Transfère-le sur le téléphone (câble, e-mail…) et installe-le
   (autoriser "installer des apps inconnues" si demandé).

L'app est 100 % Java, sans dépendance externe : elle tourne donc partout,
y compris sur les téléphones **ARMv7 32 bits** (Android 5.0 minimum).

## 3. Sur le téléphone

1. Vérifie que le PC et le téléphone sont sur le même réseau Wi-Fi.
2. Ouvre LocalTube → au premier lancement, entre l'adresse affichée par
   le serveur (ex. `http://192.168.1.50:8000`). Tu pourras la changer à
   tout moment via le menu ⋮ → **Serveur…**
3. La liste des vidéos apparaît : utilise la barre de recherche pour
   filtrer, tape une vidéo pour la lire (avec barre de progression,
   avance/recul plein écran…).

## Notes

- **Format conseillé** : MP4 (H.264 + AAC) passe sur tous les appareils.
  MKV/WebM fonctionnent selon le téléphone ; AVI est rarement lu nativement.
- Le seek (avance rapide) fonctionne car le serveur gère les requêtes
  HTTP *Range*.
- Si ça ne connecte pas : pare-feu Windows, IP changée par la box
  (relance le script pour voir la nouvelle adresse), ou VPN actif sur
  le téléphone.

---

## Mise à jour automatique via GitHub

L'app vérifie à chaque démarrage la dernière release du repo
`tear360/localtube`. Si le numéro du tag (ex. `v3`) est supérieur à sa
propre `versionCode`, elle propose de télécharger et d'installer la mise
à jour (l'APK attaché à la release). Android impose une confirmation
"Installer" au moment final — c'est inévitable hors Play Store. Au tout
 premier passage, il faudra aussi autoriser une fois
"Installer des apps inconnues" pour LocalTube.

### Publier une nouvelle version

1. Modifie le code, puis **augmente `versionCode`** dans
   `app/build.gradle` (`versionCode 3`, `versionName "1.2"`…).
2. Compile :
   ```
   gradlew.bat assembleDebug
   ```
3. Sur GitHub → repo `localtube` → **Releases → Draft a new release** :
   - Tag : `v3` (le même numéro que `versionCode`)
   - Pièce jointe : `app\build\outputs\apk\debug\app-debug.apk`
     (renommé par ex. `LocalTube-1.2.apk`)
4. Publish → toutes les installs de l'app se mettront à jour au
   prochain démarrage.

> Important : compile toujours sur ce PC (même signature debug), sinon
> Android refusera d'installer par-dessus (il faudrait désinstaller,
> ce qui efface l'adresse serveur enregistrée).
