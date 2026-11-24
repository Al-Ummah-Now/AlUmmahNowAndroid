# Play content rating and target audience

Owner decision E20-D1 (13 and over, everywhere), wiki
[E20 §4.1](https://github.com/Al-Ummah-Now/AlUmmahNowWiki/blob/main/epics/E20-system-parental-controls.md). Family Link and Play
filter on these answers. They are in [`content-rating.json`](content-rating.json) and typed into Play Console → Policy → App
content, once per app; `./gradlew checkStoreListings` (CI) checks none is missing and the cited files exist. Admin is internal
testing only and has no public rating.

| Question | Viewer, TV | Studio | Why |
|---|---|---|---|
| Content rating (IARC) | Entertainment: video streaming | Utility: content creation | One questionnaire per app. Expected result: Teen / PEGI 12; keep what IARC returns |
| Users interact or exchange content | No | Yes | Viewers have no comments, chat or uploads yet. **Yes in the same PR that adds one.** Studio publishes uploads |
| Shares location, digital purchases, unrestricted internet, gambling, ads | No | No | No such permission, SDK or screen |
| Violence, controlled substances | Infrequent; references | None | Creators' talks and news can touch them. Videos above that are age-restricted (signed-in 18+) |
| Target audience | 13–15, 16–17, 18 and over | Same | Any group under 13 would put the whole app under the Families policy |
| Could appeal to children | No | No | Keep screenshots, icon and texts free of cartoon children's content; no "for kids" or "for children" |
| Restrict Minor Access | Off | Off | For adult-only apps |
| Store category | Video Players & Editors | Same | Not Social |

Before a writing feature ships in an app, Play's user-generated content policy needs, in that app: the Terms accepted before
the first post, reporting of videos, comments and users, and blocking of users.
