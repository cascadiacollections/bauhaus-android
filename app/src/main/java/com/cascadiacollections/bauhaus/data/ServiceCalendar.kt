package com.cascadiacollections.bauhaus.data

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date the bauhaus service would currently call "today".
 *
 * The service keys every artwork by **UTC** date — `datePath()` in the Worker
 * splits a `YYYY-MM-DD` key straight out of the pipeline's `utc_today()`. A
 * device-local `LocalDate.now()` therefore names a different day than the service
 * does for part of every day: it runs ahead east of UTC and behind west of it. In
 * UTC+13 that meant thirteen hours a day where `/api/<local today>.json` was a
 * guaranteed `404`.
 *
 * This is the best guess available before the service has answered. Once
 * `/api/today.json` returns, [ArtworkMetadata.publishedDate] is authoritative and
 * should replace it — it also accounts for the window between 00:00 UTC and the
 * 04:00 UTC publish run, which no clock arithmetic can predict.
 */
fun serviceToday(): LocalDate = LocalDate.now(ZoneOffset.UTC)
