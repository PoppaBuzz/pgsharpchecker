// Example Kotlin code to update status with color-coded indicators

class MainActivity : AppCompatActivity() {

    private fun updateStatus(status: VersionStatus) {
        val statusTextView = findViewById<TextView>(R.id.tvStatus)
        val statusIcon = findViewById<ImageView>(R.id.ivStatusIcon)

        when (status) {
            VersionStatus.UP_TO_DATE -> {
                statusTextView.text = "Your Pokemon Go version matches PGSharp!"
                statusTextView.setTextColor(getColor(R.color.status_success))
                statusIcon.setImageResource(android.R.drawable.ic_menu_check_mark)
                statusIcon.setColorFilter(getColor(R.color.status_success))
            }
            VersionStatus.UPDATE_AVAILABLE -> {
                statusTextView.text = "Update available! New version found."
                statusTextView.setTextColor(getColor(R.color.status_warning))
                statusIcon.setImageResource(android.R.drawable.ic_menu_info_details)
                statusIcon.setColorFilter(getColor(R.color.status_warning))
                findViewById<Button>(R.id.btnDownloadUpdate).visibility = View.VISIBLE
            }
            VersionStatus.ERROR -> {
                statusTextView.text = "Unable to check for updates. Please try again."
                statusTextView.setTextColor(getColor(R.color.status_error))
                statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                statusIcon.setColorFilter(getColor(R.color.status_error))
            }
            VersionStatus.CHECKING -> {
                statusTextView.text = "Checking for updates..."
                statusTextView.setTextColor(getColor(R.color.status_info))
                statusIcon.setImageResource(android.R.drawable.ic_popup_sync)
                statusIcon.setColorFilter(getColor(R.color.status_info))
            }
        }
    }

    enum class VersionStatus {
        UP_TO_DATE, UPDATE_AVAILABLE, ERROR, CHECKING
    }
}