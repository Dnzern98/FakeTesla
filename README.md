# FakeTesla

Citroën ë-C3 için Tesla tarzı rota ve şarj planlayıcı (Android + Android Auto).

- OBD (Vgate vLinker MC+ BLE) ile canlı şarj yüzdesi, voltaj, akım, güç
- Google Haritalar ile adres arama, rota, yol üstündeki hızlı şarj istasyonları
- Şarj durakları, şarj süreleri, varış saati ve varıştaki batarya yüzdesi
- Google Haritalar'a duraklarla birlikte navigasyon aktarımı
- Android Auto ekranı: batarya, menzil, sonraki şarj durağı

Google anahtarı GitHub'da **Settings → Secrets and variables → Actions** altında `MAPS_API_KEY` adlı gizli değer olarak durur.

APK her push'ta GitHub Actions ile derlenir: Actions → son çalışma → Artifacts → FakeTesla-apk.
