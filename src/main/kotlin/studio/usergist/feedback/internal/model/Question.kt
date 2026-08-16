package studio.usergist.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A question rendered inside a feedback prompt. Matches the v1 four
 * supported types from DEV_PRD §7.
 */
@Serializable
internal sealed class Question {

    abstract val id: String
    abstract val title: String
    abstract val subtitle: String?

    @Serializable
    @SerialName("rating")
    data class Rating(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        /** 5 or 10. Any other value is clamped at render time. */
        val scale: Int = 5,
        @SerialName("lowLabel") val lowLabel: String? = null,
        @SerialName("highLabel") val highLabel: String? = null,
    ) : Question()

    @Serializable
    @SerialName("nps")
    data class Nps(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        @SerialName("followUp") val followUp: String? = null,
    ) : Question()

    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val options: List<Choice> = emptyList(),
        @SerialName("multiSelect") val multiSelect: Boolean = false,
    ) : Question() {
        @Serializable
        data class Choice(val id: String, val label: String)
    }

    @Serializable
    @SerialName("short_text")
    data class ShortText(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val placeholder: String? = null,
        @SerialName("maxLength") val maxLength: Int? = null,
    ) : Question()
}
