/*
 * Copyright (C) 2021 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.scanner.experimental

import java.time.Instant

import kotlin.time.measureTimedValue

import org.ossreviewtoolkit.downloader.DownloadException
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.UnknownProvenance
import org.ossreviewtoolkit.model.config.DownloaderConfiguration
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.utils.collectMessagesAsString
import org.ossreviewtoolkit.utils.createOrtTempDir
import org.ossreviewtoolkit.utils.log

class ExperimentalScanner(
    val scannerConfig: ScannerConfiguration,
    val downloaderConfig: DownloaderConfiguration,
    val provenanceDownloader: ProvenanceDownloader,
    val storageReaders: List<ScanStorageReader>,
    val storageWriters: List<ScanStorageWriter>,
    val packageProvenanceResolver: PackageProvenanceResolver,
    val nestedProvenanceResolver: NestedProvenanceResolver,
    val scannerWrappers: List<ScannerWrapper>
) {
    suspend fun scan(ortResult: OrtResult): Map<Package, NestedProvenanceScanResult> =
        scan((ortResult.getProjects().map { it.toPackage() } + ortResult.getPackages().map { it.pkg }).toSet())

    suspend fun scan(packages: Set<Package>): Map<Package, NestedProvenanceScanResult> {
        log.info { "Resolving provenance for ${packages.size} packages." }
        // TODO: Handle issues for packages where provenance cannot be resolved.
        val (packageProvenances, packageProvenanceDuration) = measureTimedValue { getPackageProvenances(packages) }
        log.info {
            "Resolved provenance for ${packages.size} packages in ${packageProvenanceDuration.inWholeMilliseconds}ms."
        }

        log.info { "Resolving source trees for ${packages.size} packages." }
        val (nestedProvenances, nestedProvenanceDuration) =
            measureTimedValue { getNestedProvenances(packageProvenances) }
        log.info {
            "Resolved source trees for ${packages.size} packages in ${nestedProvenanceDuration.inWholeMilliseconds}ms."
        }

        val allKnownProvenances = (
                packageProvenances.values.filterIsInstance<KnownProvenance>() +
                        nestedProvenances.values.flatMap { nestedProvenance ->
                            nestedProvenance.subRepositories.values
                        }
                ).toSet()

        val scanResults = mutableMapOf<ScannerWrapper, MutableMap<KnownProvenance, List<ScanResult>>>()

        // Get stored scan results for each ScannerWrapper by provenance.
        log.info {
            "Reading stored scan results for ${packageProvenances.size} packages with ${allKnownProvenances.size} " +
                    "provenances."
        }
        val (storedResults, readDuration) = measureTimedValue {
            getStoredResults(allKnownProvenances, packageProvenances)
        }
        log.info {
            "Read stored scan results in ${readDuration}ms:\n${
                scanResults.entries.joinToString("\n") { (scanner, results) ->
                    "\t${scanner.name}: Results for ${results.size}/${allKnownProvenances.size} provenances."
                }
            }"
        }
        scanResults += storedResults

        // Check which packages have incomplete scan results.
        val packagesWithIncompleteScanResult =
            getPackagesWithIncompleteResults(packages, packageProvenances, nestedProvenances, scanResults)

        log.info { "${packagesWithIncompleteScanResult.size} packages have incomplete scan results." }

        log.info { "Starting scan of missing packages if any package based scanners are configured." }

        // Scan packages with incomplete scan results.
        packagesWithIncompleteScanResult.forEach { (pkg, scanners) ->
            // TODO: Move to function.
            // TODO: Verify that there are still missing scan results for the package, previous scan of another package
            //       from the same repository could have fixed that already.
            scanners.filterIsInstance<PackageBasedRemoteScannerWrapper>().forEach { scanner ->
                log.info { "Scanning ${pkg.id.toCoordinates()} with package based remote scanner ${scanner.name}." }

                // Scan whole package with remote scanner.
                // TODO: Use coroutines to execute scanners in parallel.
                val scanResult = scanner.scanPackage(pkg)

                log.info {
                    "Scan of ${pkg.id.toCoordinates()} with package based remote scanner ${scanner.name} finished."
                }

                // Split scan results by provenance and add them to the map of scan results.
                val nestedProvenanceScanResult =
                    scanResult.toNestedProvenanceScanResult(nestedProvenances.getValue(pkg))
                nestedProvenanceScanResult.scanResults.forEach { (provenance, scanResultsForProvenance) ->
                    val scanResultsForScanner = scanResults.getOrPut(scanner) { mutableMapOf() }
                    scanResultsForScanner[provenance] =
                        scanResultsForScanner[provenance].orEmpty() + scanResultsForProvenance

                    // TODO: Write new results to storages.
                }
            }
        }

        // Check which provenances have incomplete scan results.
        val provenancesWithIncompleteScanResults =
            getProvenancesWithIncompleteScanResults(allKnownProvenances, scanResults)

        log.info { "${provenancesWithIncompleteScanResults.size} provenances have incomplete scan results." }

        log.info { "Starting scan of missing provenances if any provenance based scanners are configured." }

        provenancesWithIncompleteScanResults.forEach { (provenance, scanners) ->
            // Scan provenances with remote scanners.
            // TODO: Move to function.
            scanners.filterIsInstance<ProvenanceBasedRemoteScannerWrapper>().forEach { scanner ->
                log.info { "Scanning $provenance with provenance based remote scanner ${scanner.name}." }

                // TODO: Use coroutines to execute scanners in parallel.
                val scanResult = scanner.scanProvenance(provenance)

                log.info {
                    "Scan of $provenance with provenance based remote scanner ${scanner.name} finished."
                }

                val scanResultsForScanner = scanResults.getOrPut(scanner) { mutableMapOf() }
                scanResultsForScanner[provenance] = scanResultsForScanner[provenance].orEmpty() + scanResult

                storageWriters.filterIsInstance<ProvenanceBasedScanStorageWriter>().forEach { writer ->
                    writer.write(provenance, scanResult)
                }
            }

            // Scan provenances with local scanners.
            val localScanners = scanners.filterIsInstance<LocalScannerWrapper>()
            if (localScanners.isNotEmpty()) {
                val localScanResults = scanLocal(provenance, localScanners)

                localScanResults.forEach { (scanner, scanResult) ->
                    val scanResultsForScanner = scanResults.getOrPut(scanner) { mutableMapOf() }
                    scanResultsForScanner[provenance] = scanResultsForScanner[provenance].orEmpty() + scanResult

                    storageWriters.filterIsInstance<ProvenanceBasedScanStorageWriter>().forEach { writer ->
                        writer.write(provenance, scanResult)
                    }
                }
            }
        }

        val nestedProvenanceScanResults = createNestedProvenanceScanResults(packages, nestedProvenances, scanResults)

        packagesWithIncompleteScanResult.forEach { (pkg, _) ->
            storageWriters.filterIsInstance<PackageBasedScanStorageWriter>().forEach { writer ->
                nestedProvenanceScanResults[pkg]?.let { nestedProvenanceScanResult ->
                    // TODO: Save only results for scanners which did not have a stored result.
                    writer.write(pkg, nestedProvenanceScanResult)
                }
            }
        }

        return nestedProvenanceScanResults
    }

    private fun getPackageProvenances(packages: Set<Package>): Map<Package, Provenance> =
        packages.associateWith { pkg ->
            packageProvenanceResolver.resolveProvenance(pkg, downloaderConfig.sourceCodeOrigins)
        }

    private fun getNestedProvenances(packageProvenances: Map<Package, Provenance>): Map<Package, NestedProvenance> {
        return packageProvenances.mapValues { (_, provenance) ->
            when (provenance) {
                is KnownProvenance -> nestedProvenanceResolver.resolveNestedProvenance(provenance)
                is UnknownProvenance -> null
            }
        }.filterNotNullValues()
    }

    private fun getStoredResults(
        provenances: Set<KnownProvenance>,
        packageProvenances: Map<Package, Provenance>
    ): Map<ScannerWrapper, MutableMap<KnownProvenance, List<ScanResult>>> {
        return scannerWrappers.associateWith { scanner ->
            val scannerCriteria = scanner.getScannerCriteria()
            val result = mutableMapOf<KnownProvenance, List<ScanResult>>()

            provenances.forEach { provenance ->
                if (result[provenance] == null) {
                    storageReaders.forEach { reader ->
                        when (reader) {
                            is PackageBasedScanStorageReader -> {
                                packageProvenances.entries.find { it.value == provenance }?.key?.let { pkg ->
                                    reader.read(pkg, scannerCriteria).forEach { scanResult2 ->
                                        // TODO: Do not overwrite entries from other storages in result.
                                        // TODO: Map scan result to known source tree for package.
                                        result += scanResult2.scanResults
                                    }
                                }
                            }

                            is ProvenanceBasedScanStorageReader -> {
                                // TODO: Do not overwrite entries from other storages in result.
                                result[provenance] = reader.read(provenance, scannerCriteria)
                            }
                        }
                    }
                }
            }

            result
        }
    }

    private fun getPackagesWithIncompleteResults(
        packages: Set<Package>,
        packageProvenances: Map<Package, Provenance>,
        nestedProvenances: Map<Package, NestedProvenance>,
        scanResults: Map<ScannerWrapper, Map<KnownProvenance, List<ScanResult>>>,
    ): Map<Package, List<ScannerWrapper>> {
        return packages.associateWith { pkg ->
            scannerWrappers.filter { scanner ->
                val hasPackageProvenanceResult = scanResults.getValue(scanner)[packageProvenances.getValue(pkg)] != null
                val hasAllNestedProvenanceResults = nestedProvenances[pkg]?.let { nestedProvenance ->
                    scanResults.getValue(scanner)[nestedProvenance.root] != null &&
                            nestedProvenance.subRepositories.all { (_, provenance) ->
                                scanResults.getValue(scanner)[provenance] != null
                            }
                } != false

                !hasPackageProvenanceResult || !hasAllNestedProvenanceResults
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun getProvenancesWithIncompleteScanResults(
        provenances: Set<KnownProvenance>,
        scanResults: Map<ScannerWrapper, Map<KnownProvenance, List<ScanResult>>>
    ): Map<KnownProvenance, List<ScannerWrapper>> {
        return provenances.associateWith { provenance ->
            scannerWrappers.filter { scanner ->
                scanResults.getValue(scanner)[provenance] == null
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun createNestedProvenanceScanResults(
        packages: Set<Package>,
        //packageProvenances: Map<Package, Provenance>,
        nestedProvenances: Map<Package, NestedProvenance>,
        scanResults: Map<ScannerWrapper, Map<KnownProvenance, List<ScanResult>>>
    ): Map<Package, NestedProvenanceScanResult> =
        packages.associateWith { pkg ->
            val nestedProvenance = nestedProvenances.getValue(pkg)
            val provenances = setOf(nestedProvenance.root, *nestedProvenance.subRepositories.values.toTypedArray())
            val scanResultsByProvenance = provenances.associateWith { provenance ->
                scanResults.values.flatMap { it[provenance].orEmpty() }
            }
            NestedProvenanceScanResult(nestedProvenance, scanResultsByProvenance)
        }

    private fun scanLocal(
        provenance: KnownProvenance,
        scanners: List<LocalScannerWrapper>
    ): Map<LocalScannerWrapper, ScanResult> {
        val downloadDir = createOrtTempDir() // TODO: Use provided download dir instead.

        try {
            provenanceDownloader.download(provenance, downloadDir)
        } catch (e: DownloadException) {
            val message = "Could not download provenance $provenance: ${e.collectMessagesAsString()}"
            log.error { message }

            val summary = ScanSummary(
                startTime = Instant.now(),
                endTime = Instant.now(),
                packageVerificationCode = "",
                licenseFindings = sortedSetOf(),
                copyrightFindings = sortedSetOf(),
                issues = listOf(
                    OrtIssue(
                        source = "Downloader",
                        message = message,
                        severity = Severity.ERROR
                    )
                )
            )

            return scanners.associateWith { scanner ->
                ScanResult(
                    provenance = provenance,
                    scanner = scanner.details,
                    summary = summary
                )
            }
        }

        return scanners.associateWith { scanner ->
            log.info { "Scanning $provenance with local scanner ${scanner.name}." }

            scanner.scanPath(downloadDir).copy(provenance = provenance).also {
                log.info { "Scan of $provenance with provenance based remote scanner ${scanner.name} finished." }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <K, V> Map<K, V?>.filterNotNullValues(): Map<K, V> = filterValues { it != null } as Map<K, V>

/**
 * Split this [ScanResult] into separate results for each [KnownProvenance] contained in the [nestedProvenance] by matching
 * the paths of findings with the paths in the source tree.
 */
fun ScanResult.toNestedProvenanceScanResult(nestedProvenance: NestedProvenance): NestedProvenanceScanResult {
    val provenanceByPath = listOf(
        "" to nestedProvenance.root,
        *nestedProvenance.subRepositories.entries.map { it.key to it.value }.toTypedArray()
    ).sortedByDescending { it.first.length }

    val copyrightFindingsByProvenance = summary.copyrightFindings.groupBy { copyrightFinding ->
        provenanceByPath.first { copyrightFinding.location.path.startsWith(it.first) }.second
    }

    val licenseFindingsByProvenance = summary.licenseFindings.groupBy { licenseFinding ->
        provenanceByPath.first { licenseFinding.location.path.startsWith(it.first) }.second
    }

    val provenances = setOf(nestedProvenance.root, *nestedProvenance.subRepositories.values.toTypedArray())
    val scanResultsByProvenance = provenances.associateWith { provenance ->
        // TODO: Find a solution for the incorrect fileCount and packageVerificationCode and for how to associate issues
        //       to the correct scan result.
        listOf(
            copy(
                summary = summary.copy(
                    licenseFindings = licenseFindingsByProvenance[provenance].orEmpty().toSortedSet(),
                    copyrightFindings = copyrightFindingsByProvenance[provenance].orEmpty().toSortedSet()
                )
            )
        )
    }

    return NestedProvenanceScanResult(nestedProvenance, scanResultsByProvenance)
}
