# Changelog

## v1.1.1
- GitHub에서 서명된 릴리스를 빌드할 수 있도록 고정 서명 키 + CI 릴리스 자동화 추가
  - 기존에는 서명 키스토어가 로컬 PC에만 있어 GitHub 소스만으로는 서명 빌드가 불가능했음
  - 새 서명 키를 발급해 GitHub Secrets에 등록하고, 태그 푸시 시 서명된 APK가 릴리스에 자동 업로드됨 (pocket-locker와 동일 방식)
  - **서명 키가 바뀌어서 기존 v1.1.0 설치본은 삭제 후 재설치 필요** (이후 버전부터는 그대로 업데이트 설치됨)
  - 앱 기능 변경 없음

## v1.1.0
- Add mixed Korean/English TTS switching inside a single announcement.
- Add per-language TTS engine settings.
- Add English US/UK variant selection.
- Release APK is signed with the project release keystore.
