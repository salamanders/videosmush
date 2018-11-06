import com.natpryce.konfig.*
import org.bytedeco.javacpp.avutil

class Conf {
    init {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET) // ffmpeg gets loud per frame otherwise
    }

    private val inputFile by stringType
    private val outputSeconds by intType
    private val outputFPS by intType
    private val outputFile by stringType

    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationMap(
                    "inputFile" to "input.mp4",
                    "outputFile" to "output.mp4",
                    "outputSeconds" to "30",
                    "outputFPS" to "30"
            )

    val inputFileName: String
        get() = config[inputFile]

    val outputFileName: String
        get() = config[outputFile]

    val goalOutputFrames: Int
        get() = config[outputSeconds] * config[outputFPS]

    val outputFramesPerSecond: Int
        get() = config[outputFPS]
}