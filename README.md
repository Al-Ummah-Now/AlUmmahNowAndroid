# AlUmmahNowAndroid

Al Ummah Now native Android apps in Kotlin and Jetpack Compose: the viewer and Studio for phone, tablet and foldable, Android TV / Google TV,
Fire TV and Android Auto.

| App | Module | Store id |
|---|---|---|
| Al Ummah Now (viewer + Android Auto) | `viewer-mobile` | `net.alummahnow.app` |
| Al Ummah Now TV (Android TV / Google TV, Fire TV) | `viewer-tv` | `net.alummahnow.tv` |
| Al Ummah Now Studio | `studio-mobile` | `net.alummahnow.studio` |

- Plan and architecture: [`docs/PLAN.md`](docs/PLAN.md)
- Release and signing: [`docs/RELEASE.md`](docs/RELEASE.md)
- Passkeys (Studio, Admin) and Digital Asset Links: [`docs/PASSKEYS.md`](docs/PASSKEYS.md)
- Contributor and agent rules: [`CLAUDE.md`](CLAUDE.md)

```bash
./gradlew assembleDebug                    # build everything (JDK 21)
./gradlew :viewer-mobile:installQaDebug    # viewer against the QA API
```
