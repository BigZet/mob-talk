# Mob Talk0.2 implementation progress

Spec: docs/mobtalk-0.2-plan.md and user-approved detailed conversation plan.

- Core: assigned settings_ui — persistent profiles/relations/prompts/action DTOs.
- Game: assigned mob_actions_plan — authoritative context/native behaviors/client integration.
- Catalog: assigned fast_models_check — 82 species/246 real public voices.
- Root: config/localSTT/comparison/menu/integration/verification/release.

Ruling: reuse clean dedicated codex/mob-talk checkout, no extra worktree or baseline audit — user's hobby/minimal-check policy.
Ruling: no bulk paid voice probes — metadata/preview availability and actual inference statuses remain distinct; real test by user request/first use.

## Implementation checkpoint

- Core initial:27targetedtests passed;sharedstore/relations/DTO/prompts.
- Catalog:82species,246mainvoices,82speciesreserves+4candidates,332realpublicmetadataIDs;7strictcatalogtests passed. No paidprobes/listening claimed. Publicexport came from alreadyauthenticatedChromeVoiceLibrary via supportedUI/CDP publicresponses, no credentials exported; temporaryloopbackimportUI saved it; importer stopped.
- Game:context/nativeactions/observers/mixins/clientpipeline. compileClientJava passed before subsequentimprovements;finalassemble pending.
- UI:5tabs/nativeeditors/sameWAV5modelcomparison written;compilepending.
- Whisper:protocol2/configure/progress/cancel/beam5/context;23backendtests pass aftersilentresponsemetadatafix.
- Root:MP3preview/JLayer/publicpreview/explicitpaidprobe,actualsuccesscache:model/accountdigest/date;no rawkey.
- Corealignmentpass assignedcatalogagent:ownspeciesfallback,realv1reassignment,livearchetypeoverrides,4temperamentaxes,firstusevoicecache.
- NativeQA available supportedskyWindowsplugin;no gamelaunched. Separate devclient/newQAworld planned, no existingworld/mic/paidcalls.
- Core alignment complete: Store 10/10 and Engine 20/20; migration, own species reserve, live archetypes, four axes and reset API.
- One independent review completed. Fixed action DTO JSON blocker, atomic Whisper switching (24 backend tests pass), preview STOP resource race. Game capture additions/Panda genes/native threat targeting are being completed.
- Actual same-WAV recognition: public Russian preview 9.09 seconds; CUDA small 0.59s and large-v3-turbo 0.50s; turbo repaired the small model's split word. Model download/load times separate. No live microphone or paid APIs used.
- UI exposes temperament/preferences and draft-only personality reset, summary clearing and history clearing.
- Final assemble/tests pass (51 Java; then 11 affected Store tests after the additional history-reason regression). Subsequent native constraint/selection fixes compile and assemble successfully.
- Native QA: fresh Creative world `run/saves/й`; F7 five sections, stick selection, selected profile/context, actual hit before selection (-80), two hands and offhand count3, current time/look/shelter confirmed. FOLLOW/STOP fixture remains.
- Mob Talk0.2.0 installed in Prism `1.21.1-fabric-dev`, old JAR backed up. Helper updated and release folder/ZIP prepared. No live mic/paid API calls.
- Native action fixture prepared in saved QA world. User physically stopped Computer Use with Escape before reload; no further UI actions performed. FOLLOW/STOP and Panda remain unverified in game; dev client remains in main menu.
- Release/Prism binary refreshed after final assembly, including dependency license. Implementation complete; validation limitations recorded in mobtalk-0.2-verification.md.
