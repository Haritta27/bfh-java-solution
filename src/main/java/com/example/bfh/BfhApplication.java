package com.example.bfh;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@SpringBootApplication
public class BfhApplication implements CommandLineRunner {

    @Value("${app.name:}")
    private String name;

    @Value("${app.regNo:}")
    private String regNo;

    @Value("${app.email:}")
    private String email;

    private final RestTemplate restTemplate;

    public BfhApplication(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(BfhApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            String genUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA";

            Map<String, String> request = Map.of(
                    "name", name,
                    "regNo", regNo,
                    "email", email
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<JsonNode> response =
                    restTemplate.postForEntity(genUrl, entity, JsonNode.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                System.err.println("generateWebhook returned non-200: " + response.getStatusCode());
                return;
            }

            JsonNode body = response.getBody();
            if (body == null) {
                System.err.println("Empty response body from generateWebhook");
                return;
            }

            String webhook = body.has("webhook") ? body.get("webhook").asText() : null;
            String accessToken = body.has("accessToken") ? body.get("accessToken").asText() : null;

            if (webhook == null || accessToken == null) {
                System.err.println("Missing webhook or accessToken in response: " + body.toString());
                return;
            }

            System.out.println("Received webhook: " + webhook);
            System.out.println("Received accessToken: " + accessToken);

            int lastTwo = extractLastTwoDigits(regNo);
            boolean isOdd = (lastTwo % 2) == 1;

            // SQL queries
            final String QUESTION_1_SQL =
                    "SELECT p.amount AS SALARY, " +
                    "CONCAT(e.first_name, ' ', e.last_name) AS NAME, " +
                    "TIMESTAMPDIFF(YEAR, e.dob, CURDATE()) AS AGE, " +
                    "d.department_name AS DEPARTMENT_NAME " +
                    "FROM payments p " +
                    "JOIN employee e ON p.emp_id = e.emp_id " +
                    "JOIN department d ON e.department = d.department_id " +
                    "WHERE DAY(p.payment_time) <> 1 " +
                    "AND p.amount = ( " +
                    "  SELECT MAX(amount) " +
                    "  FROM payments " +
                    "  WHERE DAY(payment_time) <> 1 );";

            final String QUESTION_2_SQL =
                    "SELECT e.emp_id, " +
                    "e.first_name, " +
                    "e.last_name, " +
                    "d.department_name, " +
                    "(SELECT COUNT(*) " +
                    " FROM employee e2 " +
                    " WHERE e2.department = e.department " +
                    " AND e2.dob > e.dob) AS younger_employees_count " +
                    "FROM employee e " +
                    "LEFT JOIN department d ON e.department = d.department_id " +
                    "ORDER BY e.emp_id DESC;";

            String finalQuery = isOdd ? QUESTION_1_SQL : QUESTION_2_SQL;

            HttpHeaders subHeaders = new HttpHeaders();
            subHeaders.setContentType(MediaType.APPLICATION_JSON);
            subHeaders.set("Authorization", accessToken);

            Map<String, String> subBody = Map.of("finalQuery", finalQuery);
            HttpEntity<Map<String, String>> subEntity = new HttpEntity<>(subBody, subHeaders);

            String submitUrl = "https://bfhldevapigw.healthrx.co.in/hiring/testWebhook/JAVA";
            ResponseEntity<String> submitResponse =
                    restTemplate.postForEntity(submitUrl, subEntity, String.class);

            System.out.println("Submission HTTP status: " + submitResponse.getStatusCode());
            System.out.println("Submission body: " + submitResponse.getBody());

        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    private int extractLastTwoDigits(String reg) {
        String digits = reg.replaceAll("\\D", "");
        if (digits.length() == 0) return 0;
        if (digits.length() == 1) return Integer.parseInt(digits);
        String lastTwo = digits.substring(digits.length() - 2);
        return Integer.parseInt(lastTwo);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
