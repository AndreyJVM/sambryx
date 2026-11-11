package mari.samba;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Epic("Application configuration and HTTP errors")
@Feature("Spring component: application context startup")
class SambryxApplicationTests {

    @Test
    @DisplayName("The application context loads with default configuration")
    void contextLoads() {}
}
