package dev.studypath;

import static dev.studypath.StudyData.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class StudyStore implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    StudyStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        seed("programs.json");
        seed("programs-extra.json");
    }

    private void seed(String resource) throws IOException {
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            List<Program> seeds = json.readValue(stream, new TypeReference<>() {});
            for (Program seed : seeds) {
                jdbc.sql("""
                        INSERT INTO program_snapshot (program_id, snapshot_date, country, track, intake_year, definition)
                        SELECT ?, ?, ?, ?, ?, ?
                        WHERE NOT EXISTS (SELECT 1 FROM program_snapshot WHERE program_id = ? AND snapshot_date = ?)
                        """).params(seed.id(), seed.snapshot(), seed.country().name(), seed.track().name(), seed.year(),
                        encode(seed), seed.id(), seed.snapshot()).update();
            }
        }
    }

    List<Program> catalog() {
        return jdbc.sql("""
                SELECT p.definition FROM program_snapshot p
                WHERE p.snapshot_date = (SELECT MAX(q.snapshot_date) FROM program_snapshot q
                    WHERE q.program_id = p.program_id AND q.intake_year = p.intake_year)
                ORDER BY p.country DESC, p.program_id
                """).query((row, index) -> decode(row.getString(1), Program.class)).list();
    }

    void save(Assessment assessment) {
        jdbc.sql("INSERT INTO assessment (id, created_ms, report) VALUES (?, ?, ?)")
                .params(assessment.id(), assessment.createdAt(), encode(assessment)).update();
    }

    List<History> history() {
        return jdbc.sql("SELECT report FROM assessment ORDER BY created_ms DESC, id DESC LIMIT 30")
                .query((row, index) -> decode(row.getString(1), Assessment.class)).list().stream()
                .map(a -> new History(a.id(), a.createdAt(), a.profile().year(), a.profile().track(),
                        a.profile().countries(), a.results().size())).toList();
    }

    java.util.Optional<Assessment> find(String id) {
        return jdbc.sql("SELECT report FROM assessment WHERE id = ?").param(id)
                .query((row, index) -> decode(row.getString(1), Assessment.class)).optional();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the assessment or policy snapshot", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the saved assessment or policy snapshot", e);
        }
    }
}
