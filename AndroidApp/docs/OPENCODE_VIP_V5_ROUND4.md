# Round4: Cloud transfer identity + restore recovery

Tiếp tục sửa source thật trong F:/AI/T-Emulator theo ủy quyền user cho Codex giao OpenCode/Gemini và audit lặp. Không deploy/publish, không thay audit assertions. Đừng báo hoàn tất phần chưa chỉnh source/callsite. Đọc helpers auth/session được round3 tạo và dùng chung.

## 1. Pin identity xuyên suốt backup/restore

CloudBackupDialogHelper cũ chỉ kiểm tra UID trước/sau upload, không phát hiện A->logout->A hay đổi Drive. DriveAuthorizationManager dùng saved email thay actual account khi load, trong khi token lấy lastSignedInAccount; CloudBackupRecord.driveAccount lấy email hiện tại sauupload. Restore list/download/commit không pin phiên.

Capture app UID+generation và actual Drive stable ID/email+Drive generation cho operation. Token phải lấy đúng pinned account, xác minh current trước/sau gettoken, folderlookup/create, uploadsession/chunk/retry, list/download và commit. Không chọn token từ account mới cho job cũ. Saved email chỉ display; authority actual Google account có scope. Clear/disconnect/reconnect tănggeneration; mock identity chỉDEBUG. Pin account cho bản ghicloud, không lấy currentemail sauupload. Cancel khi bấtkỳidentityđổi kểcả cùngUID quaylại. Restore khôngđòiVIP nhưng vẫn auth/Drive/sessionguards, sessionguard ởngay trong commitrestore chứ không chỉ trướcdownload.

## 2. Durable transfer và cancellation

- Không swallow initial TransferRecord insert rồi upload. CloudBackupRecord + terminalCOMPLETED phải commit Room transaction, khôngtoastsuccessifDBfails.
- Khi remote uploadthànhcông nhưnglocalcommitfails hoặcprocesscrash, lưu jobmetadata đủ để reconcile orphan: backupId đượcpersist trướcupload, pinnedowner/drive, game/snapshotmetadata, remoteFileId/session khi có. RemoteappProperties đãcóbackupId, dùnglookup theoidentity đó nếucrash trướcghi remoteFileId. Recovery startup/foreground phải có callsite thật; reconcile cùng app UID/Drive actual account, giữ RECOVERY_REQUIRED nếu chưacóauth hoặcremote trạngthái chưa rõ. Không tự upload lại làmduplicate/không đẩy sangaccountkhác. Có thể journalfile durable thay schema migration nếuđủsafe, hoặc Room migrationexplicit; cấmdestructive migration làm mấtdata.
- Cancellation: rethrow CancellationException, cleanup vàpersistterminalstate trong NonCancellable. Không xóa metadata cầnrecover. Snapshotfailure cũng cậpnhật terminalstate; DBerror phải ngừng và báofailure, khôngcallbacksuccess.
- Drive OAuth pendingintent cần enum BACKUP/RESTORE, gắnappUID/generation: GamesFragment driveAuthLauncher hiện chỉresume pendingBackupGame; ViewBackups lostintent. Cấpquyền xongresume đúng restorelist; cancel/đổiphiênclear. Khôngresumebackup từ accountappkhác.

- Cùng auditidentity, MoreFragment btnDeleteAccount hiện mởdialog khôngcapturephiên, tới positivebutton mớilấyuser/getIdToken; tokenasyncvàserverdelete xongcleanup cũngkhôngcheckphiên. Pin UID+generation **khi mở confirmation**, checkkhipositive, saugetIdToken/trướcHTTP, sauresponse/trướcrevoke/signOut/disconnect. NếuA mởdialogrồiđổiB, khôngđượcdeleteB; response deleteA đếnmuộn khônglogout/disconnectB. Nếu revoke cacheA sauresponseauthoritative thì chỉcacheA, không đẩyStateFlowBfalse. Test bằng productionguard/control logic cùng sessionhelpers thật.

## 3. Restore journal / rollback (bảo toàn save)

GameRestoreService catch gọi storage.restoreFromRollbackBackup mà bỏ Boolean; finally cleanupStaging luônxóa rollback duy nhất khi phục hồi lỗi. Processcrash giữa filesystemcommit/DAOinsert chưacórecovery. Giữ rollback/journalbền vững đến lúc DBcommit hoặcrollbackxácnhậnsuccess. Nếu rollback thấtbại tuyệtđối khôngcleanup dữliệuduynhất; markerRECOVERY_REQUIREDvàlỗi rõràng. GameStorageManager copy/delete/mkdir Boolean phảicheck, khôngreturnsuccess khiops thấtbại; cleanupStaging(null) khôngxóa pendingjournal sau24h.

Journal ghi trước destructivecommit (atomicwrite/rename, flushsync phùhợp), targetgameid, old/finalGameRecord khi cần, rollbacklocation trong sandbox, stage. Recovery actualstartup phải xử lý crash trướcfilecommit, giữafilecommit/DB, sauDBcommittrướccleanup. Nếu DAOđãcommitfinalrecord nhưngmarkerchưaCOMPLETED, recoverykhông rollbackfiles đểDBtrỏsang trạngtháikhác; dùngidempotentDBstatecomparison hoặc phaseprotocol antoàn. Nếu chưacommit DB, rollbackfiles và giữ/khôi phụcoldrecord. Restore mớiDBfailed phảiremovenewfiles, cácT06/R09khôngregress. Validatejournalpaths/canonicalIDs khôngchopháfile ngoàigame/stagingroot.

Serialize restore với snapshot cùnggame và nativeemulatorđangchơi. Dùngsharedcoordinator cólease/trylock gắnEmulatorActivity native lifecycle (acquire trướcload, release saustop), snapshotcreate+restorecommit cùngcoordinator. Có thể rejectbusy bằng error; đừngđặtmutex chỉtrongservice màkhôngnối nativegamecallsite. Startgame cũngkhôngoverwrite ROM qua prepareRomFile trongkhilockrestore/snapshot. Recovery phải xonghoặc giữgameblockedtrướccho launch, khôngkhởitạo recoveryasync rồicho chơi ngay. Two restores resolve/commit phải serialize đểkhôngcùngtarget vàoverwrite ngang. Cancellation khôngbỏjournal hayleaserelease.

## Tests / bằng chứng

Codex phát hiện thêm S05 FAIL deterministic và đọc native C++ xácnhận: JNI JniBridge.cpp loadRom tạoVirtualFile(outerArchive,innerMember); Utilities/VirtualFile.cpp GetFileName() trảinnerFile; Core/Shared/SaveStateManager.cpp filename lấyinnerbasename; Core/Shared/Emulator.cpp BatteryManager.Initialize tươngtự. GameRestoreService hiện targetBasename=File(localRomPath).nameWithoutExtension => archive `outer.zip` chứa `folder/inner.nes` restoređổi `inner.sav/inner_1.mss` thành `outer.sav/outer_1.mss`, native khôngthấysave. S05 actualsnapshot->realrestore+fakeDAO test ở V5RestoreAudit; fixtureZIPcóiNESmapper0headerbank/battery, filestronggamefolder saveprefixinner. Phải giữarchiveROMfeature .zip/.7z hoạtđộng choALLvàSAVE_ONLY; mappingphảiphùhợpactualinnernativeidentity hoặcpreservevalidatednativefilenames khiarchiveidentitykhôngđổi, khôngarbitrarilyrenameouter. Đừngdisablearchivebackupđểnétest. Nếu thêmnativeidentitymetadata/JNIgetter thìpersist/use consistent vàversioncompat; cấmbịamember bằngfirstfilekhácselectionnative. Kiểmtrasamebasename/slotcollision cũngvẫnđúng. TổngV5snapshot/roundtrip5probes S01-S05.

Tests actual production coordinator/journal/recovery, khôngchỉ logicmôphỏng: pinnedappABA, đổiDrive giữUID, DBcommitfails nosuccess vàorphanrecover; rollback failure giữbackup, crashbefore/afterDBcommit recover一致, busygame reject/serialize, cancellation preservesrecovery. Test hooks/injectedFS/DAO hợp lý nếu cần deterministic faultinjection, defaultproductionimplementation thật. Roommigrationtest nếuđổischema.

Giữ backend C01-C18, snapshot S01-S04, restore T01-T06/V2/V3 và GameRestoreSecurityTest. Gradle compileDebugKotlin/compileReleaseKotlin/testDebugUnitTest --offline. Audit standalone nếuimportnewhelper cầnrunner include files, thôngbáoCodexđểCodexcậpnhật dependencylist; khôngsửaassertions.

Không đổi version/buildbundle. Report chỉfile thực sửa, test thựcchạy, giới hạnE2E (chưa códevice/authGooglethật). Billing/config/ack retry sẽgiao lượt riêng, không gọiđãhoànthành tronglượt này.

S06 actual GameStorageManager audit added: restoreFromRollbackBackup given absent rollback source currently deletes live game and returns true. Must refuse BEFORE deleting target, preserve ONLY_LIVE_SAVE. RestoreAudit now6 probes (S01-S06), preserve assertions. Empty existing rollback directory is valid for originally new game but absent/non-directory rollback is not. Recovery journal must distinguish expected empty backup from missing copy.
