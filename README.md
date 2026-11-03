# MobileRAG (File RAG)

On-device file search for Android. Describe a file or photo, see matches with the full path, open them inside the app.

Indexes: photos (ML Kit labels + OCR), PDFs (text, OCR fallback for scans), DOCX, text/code files, and file names/folders.

## Build the APK (GitHub Actions)
1. Create an empty repo, e.g. RMKTAMIL/MobileRAG
2. In this folder:
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/RMKTAMIL/MobileRAG.git
   git push -u origin main
3. GitHub -> Actions -> "Build APK" -> open the run -> download artifact `MobileRAG-apk` (contains app-debug.apk)
4. Install on the phone (allow "install unknown apps"), grant all-files access, tap Index files.
