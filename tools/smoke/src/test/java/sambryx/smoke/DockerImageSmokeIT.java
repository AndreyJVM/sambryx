package sambryx.smoke;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockerImageSmokeIT {
    @Test
    void verifiesPackagedImageAgainstTheTestedJar() throws Exception {
        String image = System.getProperty("smoke.image", "");
        String jar = System.getProperty("smoke.jar", "");
        if (image.isBlank() || image.startsWith("${") || jar.isBlank() || jar.startsWith("${")) {
            throw new IllegalArgumentException("Set smoke.image and smoke.jar for the Docker smoke profile");
        }
        DockerSmoke.verify(image, Path.of(jar), DockerSmoke::docker);
    }
}
