# PdfViewerGlass (stage 1)
Open the folder in Android Studio (Koala+ / JDK 17), let Gradle sync, run on a device/emulator (API 26+).
Stage 1: themes, navigation, Home, Settings, Image to PDF. Stage 2: PDF Reader. Stage 3: Files + Favorites (import, search, sort, share, favorite, remove from list), Recent + Continue reading, resume page (stored in DataStore).
Image to PDF (multi-select, reverse, move, remove, font, caption, page numbers, save via system picker).
Files/Favorites are placeholders labelled as such. Next: reader, scanner, file manager, PDF tools.

Stage 4: PDF Tools (Merge, Extract/Split with reorder+delete via page list, Rotate, Compress by JPEG re-save) using PDFBox-Android (Apache-2.0).
Note: the first Gradle sync downloads com.tom-roush:pdfbox-android; large PDFs are processed in memory.

Stage 5: Document Scanner (CameraX, manual 4-corner crop with perspective correction, rotate, Original/Enhance/Gray/B&W, multi-page, retake/reorder/remove, save PDF or single JPEG). No automatic edge detection, no OCR, no watermarks.

Stage 6: Tools now include Page numbers (position, format, Helvetica/Times/Courier) and Password protection (AES-128, print/copy permissions). No watermark tool by request.
