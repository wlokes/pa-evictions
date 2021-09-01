# Purpose

An alternative interface for searching the Unified Judicial System of PA's Web Portal (PA Portal).

This application specifically searches for Landlord/Tenant Dockets filed by Magisterial District Judge 03-2-10 during the provided search period.

# Search Criteria

* A docket search is performed using the PA Portal's "Filed Date" option

* The PA Portal only allows searches in 180-day intervals. If an interval greater than 180 days is provided to this application it will perform multiple searches in no more than 180-day intervals.

* Search start and end dates are inclusive

# Gotchas

* The PA Portal rate limits request from a single IP address. At the time of writing PA has not published an API guide and the rate limit is unknown.

  * If you see ERROR level log message containing "status code 429, reason Unauthorized request" it means you have reached your rate limit and the PA Portal is denying requests for PDF dockets
  * If this happens, it is best to stop the process (Control+C or CMD+C), wait a few minutes, and try the search again with a smaller interval
  * You will need to manually merge files if multiple searches are required
* In an attempt to prevent hitting the rate limit this app will 
  * Pause for 2 seconds between requests and
  * Pause for 45 seconds every 15th request 
  * These values are complete arbitrary and don't always work, but for most searches that are an interval of six months of less it seems ok.

# How to use

This project compiles to an executable JAR. To run open a terminal and run `java -jar pa-evictions.jar --startDate=2016-01-01 --endDate=2016-06-30`
