import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage

class Crystal : Application() {

    override fun start(stage: Stage) {
        val fxml_loader = FXMLLoader(javaClass.getResource("crystal.fxml"))
        val scene = Scene(fxml_loader.load())
        stage.title = "Crystal"
        stage.scene = scene
        stage.show()
    }
}

fun main() {
    Application.launch(Crystal::class.java)
}