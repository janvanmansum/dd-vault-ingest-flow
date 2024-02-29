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
package nl.knaw.dans.vaultingest.core;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.vaultingest.client.BagValidator;
import nl.knaw.dans.vaultingest.client.InvalidDepositException;
import nl.knaw.dans.vaultingest.client.VaultCatalogClient;
import nl.knaw.dans.vaultingest.core.deposit.Deposit;
import nl.knaw.dans.vaultingest.core.deposit.DepositManager;
import nl.knaw.dans.vaultingest.core.deposit.Outbox;
import nl.knaw.dans.vaultingest.core.rdabag.DefaultRdaBagWriterFactory;
import nl.knaw.dans.vaultingest.core.util.IdMinter;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Slf4j
@AllArgsConstructor
public class ConvertToRdaBagTask implements Runnable {
    private final Path path;
    private final Outbox outbox;
    private final Map<String, String> dataSupplierMap;
    private final DefaultRdaBagWriterFactory rdaBagWriterFactory;
    private final VaultCatalogClient vaultCatalogClient;
    private final BagValidator bagValidator;
    private final IdMinter idMinter;
    private final DepositManager depositManager;
    private final Path dveOutbox;

    public void run() {
        log.info("Processing deposit on path {}", path);
        try {
            var bagDir = getBagDir(path);

            log.debug("Validating deposit on path {}", bagDir);
            bagValidator.validate(bagDir);

            log.debug("Loading deposit on path {}", path);
            var deposit = depositManager.loadDeposit(path, dataSupplierMap);
            processDeposit(deposit);

            log.debug("Deposit {} processed successfully", deposit.getId());
            depositManager.saveDepositProperties(deposit);

            log.debug("Moving deposit to outbox");
            outbox.moveDeposit(deposit);
        }
        catch (InvalidDepositException e) {
            handleFailedDeposit(path, outbox, Deposit.State.REJECTED, e);
        }
        catch (Throwable e) {
            handleFailedDeposit(path, outbox, Deposit.State.FAILED, e);
        }
    }

    private void processDeposit(Deposit deposit) throws InvalidDepositException, IOException {
        if (deposit.isUpdate()) {
            // check if deposit exists in vault catalog
            var catalogDeposit = vaultCatalogClient.findDeposit(deposit.getIsVersionOf())
                .orElseThrow(() -> new InvalidDepositException(String.format("Deposit with sword token %s not found in vault catalog", deposit.getSwordToken())));

            // compare user id
            if (!StringUtils.equals(deposit.getDataSupplier(), catalogDeposit.getDataSupplier())) {
                throw new InvalidDepositException(String.format(
                    "Data supplier in deposit  %s does not match the data supplier %s in the vault catalog", deposit.getDataSupplier(), catalogDeposit.getDataSupplier()
                ));
            }

            deposit.setNbn(catalogDeposit.getNbn());
        }
        else {
            // generate nbn for new deposit
            deposit.setNbn(idMinter.mintUrnNbn());
        }

        var registeredDeposit = vaultCatalogClient.registerDeposit(deposit);
        deposit.setObjectVersion(registeredDeposit.getObjectVersion());

        // send rda bag to vault
        try {
            rdaBagWriterFactory.createRdaBagWriter(deposit).write(dveOutbox.resolve(outputFilename(deposit.getBagId(), deposit.getObjectVersion())));
            deposit.setState(Deposit.State.ACCEPTED, "Deposit accepted");
        }
        catch (Exception e) {
            throw new IllegalStateException("Error writing bag: " + e.getMessage(), e);
        }
    }

    private String outputFilename(String bagId, Long objectVersion) {
        Objects.requireNonNull(bagId);
        Objects.requireNonNull(objectVersion);

        // strip anything before all colons (if present), and also the colon itself
        bagId = bagId.toLowerCase().replaceAll(".*:", "");

        return String.format("vaas-%s-v%s.zip", bagId, objectVersion);
    }

    private void handleFailedDeposit(Path path, Outbox outbox, Deposit.State state, Throwable error) {
        log.error("Deposit on path {} failed with state {}", path, state, error);

        try {
            depositManager.updateDepositState(path, state, error.getMessage());
            log.info("Moving deposit to outbox: {}", path);
            outbox.move(path, state);
        }
        catch (Throwable e) {
            log.error("Failed to update deposit state and move deposit to outbox", e);

            try {
                log.info("Just moving deposit to outbox: {}", path);
                outbox.move(path, Deposit.State.FAILED);
            }
            catch (IOException ioException) {
                log.error("Failed to move deposit to outbox, nothing left to do", ioException);
            }
        }
    }

    private Path getBagDir(Path path) throws InvalidDepositException {
        try (var list = Files.list(path)) {
            return list.filter(Files::isDirectory)
                .findFirst()
                .orElse(null);
        }
        catch (IOException e) {
            return null;
        }
    }
}
