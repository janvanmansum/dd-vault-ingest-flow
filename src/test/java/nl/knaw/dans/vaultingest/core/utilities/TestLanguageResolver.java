/*
 * Copyright (C) 2023 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.vaultingest.core.utilities;

import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.vaultingest.core.deposit.LanguageResolver;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class TestLanguageResolver implements LanguageResolver {
    private final Map<String, String> iso2;
    private final Map<String, String> iso3;

    public TestLanguageResolver() {
        this.iso2 = loadCsv(getClass().getResource("/debug-etc/iso639-1-to-dv.csv"), "ISO639-1");
        this.iso3 = loadCsv(getClass().getResource("/debug-etc/iso639-2-to-dv.csv"), "ISO639-2");
    }


    @Override
    public String resolve(String language) {
        if (language == null) {
            return null;
        }

        if (language.length() == 2) {
            return iso2.get(language);
        } else if (language.length() == 3) {
            return iso3.get(language);
        }

        return null;
    }

    private Map<String, String> loadCsv(URL url, String keyColumn) {
        try {
            var path = Path.of(url.toURI().getPath());

            try (var parser = CSVParser.parse(path, StandardCharsets.UTF_8, CSVFormat.RFC4180.withFirstRecordAsHeader())) {
                var result = new HashMap<String, String>();

                for (var record : parser) {
                    result.put(record.get(keyColumn), record.get("Dataverse-language"));
                }

                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Could not load csv", e);
            return Map.of();
        }
    }
}
