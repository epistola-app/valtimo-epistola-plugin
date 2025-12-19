package app.epistola.valtimo;

import com.ritense.valtimo.contract.config.LiquibaseRunnerAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackageClasses = {LiquibaseRunnerAutoConfiguration.class}
)
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}