package com.workez365.smsbridge

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Firestore 경로를 중앙에서 관리하는 헬퍼 오브젝트
 *
 * 유저별 데이터 격리 구조:
 *   /users/{uid}/sms_inbox/{msg_id}      ← Slave가 수신한 SMS
 *   /users/{uid}/sms_commands/{cmd_id}   ← Master가 요청한 발송 명령
 *   /users/{uid}/devices/{device_id}     ← 등록된 기기 목록 + FCM 토큰
 *
 * 모든 Firestore 접근은 이 파일의 함수를 통해서만 수행 → 경로 오타 방지
 */
object FirestorePaths {

    const val COL_INBOX    = "sms_inbox"
    const val COL_COMMANDS = "sms_commands"
    const val COL_DEVICES  = "devices"

    private val db get() = FirebaseFirestore.getInstance()

    /**
     * 현재 로그인한 유저의 uid를 반환
     * 로그아웃 상태에서 호출 시 예외 발생 → 항상 로그인 후 호출할 것
     */
    fun currentUid(): String =
        FirebaseAuth.getInstance().currentUser?.uid
            ?: error("로그인이 필요합니다. LoginActivity를 먼저 통과해야 합니다.")

    /** /users/{uid}/sms_inbox */
    fun inboxCollection(): CollectionReference =
        db.collection("users").document(currentUid()).collection(COL_INBOX)

    /** /users/{uid}/sms_commands */
    fun commandsCollection(): CollectionReference =
        db.collection("users").document(currentUid()).collection(COL_COMMANDS)

    /** /users/{uid}/devices */
    fun devicesCollection(): CollectionReference =
        db.collection("users").document(currentUid()).collection(COL_DEVICES)
}
