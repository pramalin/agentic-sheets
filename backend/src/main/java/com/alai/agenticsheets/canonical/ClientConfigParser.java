package com.alai.agenticsheets.canonical;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The only code in this project that reads a
  * {@code client-configs/*.yaml} file. See {@link CanonicalModelParser}
  * for the same reasoning applied to the (considerably larger) canonical
  * model format. */
@Component
public class ClientConfigParser {

    public ClientConfig parse(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded;
        try (InputStream in = Files.newInputStream(file)) {
            loaded = yaml.load(in);
        } catch (Exception e) {
            throw new CanonicalConfigException("unable to read/parse YAML: " + file, e);
        }
        if (!(loaded instanceof Map)) {
            throw new CanonicalConfigException("empty or invalid config file: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) loaded;

        Object clientObj = doc.get("client");
        Object dateFormatObj = doc.get("dateFormat");
        if (!(clientObj instanceof String clientId) || clientId.isBlank()) {
            throw new CanonicalConfigException("missing or invalid required 'client': " + file);
        }
        if (!(dateFormatObj instanceof String dateFormat) || dateFormat.isBlank()) {
            throw new CanonicalConfigException("missing or invalid required 'dateFormat': " + file);
        }
        return new ClientConfig(clientId, dateFormat);
    }
}
