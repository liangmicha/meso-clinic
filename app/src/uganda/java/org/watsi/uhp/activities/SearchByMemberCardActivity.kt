package org.watsi.uhp.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import kotlinx.android.synthetic.main.activity_qr_code.search_button_container
import org.watsi.device.managers.Logger
import org.watsi.domain.entities.Member
import org.watsi.domain.repositories.MemberRepository
import org.watsi.uhp.R
import javax.inject.Inject

class SearchByMemberCardActivity : QrCodeActivity() {

    @Inject lateinit var memberRepository: MemberRepository
    @Inject lateinit var logger: Logger

    companion object {
        const val RESULT_LOOKUP_FAILED = 3
        const val RESULT_REDIRECT_TO_SEARCH_FRAGMENT = 4
        const val MEMBER_RESULT_KEY = "member"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        search_button_container.visibility = View.VISIBLE
        search_button_container.setOnClickListener {
            setResult(RESULT_REDIRECT_TO_SEARCH_FRAGMENT)
            finish()
        }
    }

    override fun onDetectedQrCode(qrCode: String) {
        if (!Member.isValidCardId(qrCode)) {
            logger.warning("Invalid card ID scanned", mapOf(Pair("cardId", qrCode)))
            setErrorMessage(getString(R.string.scan_member_card_invalid_id))
        } else {
            memberRepository.findByCardId(qrCode).subscribe({
                val resultIntent = Intent().apply {
                    putExtra(MEMBER_RESULT_KEY, it)
                }
                setResult(RESULT_OK, resultIntent)
                vibrate()
                finish()

            }, {
                logger.error(it)
                finishAsFailure(RESULT_LOOKUP_FAILED)
            }, {
                setErrorMessage(getString(R.string.scan_member_card_not_found))
            })
        }
    }
}
