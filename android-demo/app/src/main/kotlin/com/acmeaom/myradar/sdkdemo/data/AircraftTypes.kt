package com.acmeaom.myradar.sdkdemo.data

/*
 * Small lookup tables: ICAO aircraft type codes to readable names, and IATA airline codes to
 * the ICAO prefixes used in ADS-B callsigns.
 */

object AircraftTypes {
    private val names = mapOf(
        "A19N" to "Airbus A319neo", "A20N" to "Airbus A320neo", "A21N" to "Airbus A321neo",
        "A318" to "Airbus A318", "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
        "A332" to "Airbus A330-200", "A333" to "Airbus A330-300", "A338" to "Airbus A330-800neo",
        "A339" to "Airbus A330-900neo", "A342" to "Airbus A340-200", "A343" to "Airbus A340-300",
        "A346" to "Airbus A340-600", "A359" to "Airbus A350-900", "A35K" to "Airbus A350-1000",
        "A388" to "Airbus A380-800", "A306" to "Airbus A300-600", "A310" to "Airbus A310",
        "B37M" to "Boeing 737 MAX 7", "B38M" to "Boeing 737 MAX 8", "B39M" to "Boeing 737 MAX 9",
        "B3XM" to "Boeing 737 MAX 10", "B712" to "Boeing 717-200", "B733" to "Boeing 737-300",
        "B734" to "Boeing 737-400", "B735" to "Boeing 737-500", "B736" to "Boeing 737-600",
        "B737" to "Boeing 737-700", "B738" to "Boeing 737-800", "B739" to "Boeing 737-900",
        "B744" to "Boeing 747-400", "B748" to "Boeing 747-8", "B752" to "Boeing 757-200",
        "B753" to "Boeing 757-300", "B762" to "Boeing 767-200", "B763" to "Boeing 767-300",
        "B764" to "Boeing 767-400", "B772" to "Boeing 777-200", "B77L" to "Boeing 777-200LR",
        "B773" to "Boeing 777-300", "B77W" to "Boeing 777-300ER", "B778" to "Boeing 777-8",
        "B779" to "Boeing 777-9", "B788" to "Boeing 787-8 Dreamliner", "B789" to "Boeing 787-9 Dreamliner",
        "B78X" to "Boeing 787-10 Dreamliner", "MD11" to "McDonnell Douglas MD-11",
        "MD88" to "McDonnell Douglas MD-88", "MD90" to "McDonnell Douglas MD-90", "E170" to "Embraer E170",
        "E175" to "Embraer E175", "E190" to "Embraer E190", "E195" to "Embraer E195",
        "E290" to "Embraer E190-E2", "E295" to "Embraer E195-E2", "E45X" to "Embraer ERJ-145",
        "E135" to "Embraer ERJ-135", "E145" to "Embraer ERJ-145", "CRJ2" to "Bombardier CRJ-200",
        "CRJ7" to "Bombardier CRJ-700", "CRJ9" to "Bombardier CRJ-900", "CRJX" to "Bombardier CRJ-1000",
        "BCS1" to "Airbus A220-100", "BCS3" to "Airbus A220-300", "DH8A" to "De Havilland Dash 8-100",
        "DH8B" to "De Havilland Dash 8-200", "DH8C" to "De Havilland Dash 8-300",
        "DH8D" to "De Havilland Dash 8-400", "AT72" to "ATR 72", "AT75" to "ATR 72-500",
        "AT76" to "ATR 72-600", "AT45" to "ATR 42-500", "SF34" to "Saab 340", "C208" to "Cessna 208 Caravan",
        "PC12" to "Pilatus PC-12", "C172" to "Cessna 172", "C182" to "Cessna 182", "SR22" to "Cirrus SR22",
        "BE20" to "Beechcraft King Air 200", "B350" to "Beechcraft King Air 350", "GLF4" to "Gulfstream IV",
        "GLF5" to "Gulfstream V", "GLF6" to "Gulfstream G650", "G280" to "Gulfstream G280",
        "CL35" to "Bombardier Challenger 350", "CL60" to "Bombardier Challenger 600",
        "GL7T" to "Bombardier Global 7500", "C56X" to "Cessna Citation Excel",
        "C68A" to "Cessna Citation Latitude", "C25A" to "Cessna CitationJet CJ2",
        "C25B" to "Cessna CitationJet CJ3", "C750" to "Cessna Citation X", "E55P" to "Embraer Phenom 300",
        "E50P" to "Embraer Phenom 100", "LJ45" to "Learjet 45", "LJ60" to "Learjet 60",
        "F2TH" to "Dassault Falcon 2000", "FA7X" to "Dassault Falcon 7X", "FA8X" to "Dassault Falcon 8X",
        "H60" to "Sikorsky S-70 Black Hawk", "EC35" to "Airbus H135", "EC45" to "Airbus H145",
        "A109" to "Leonardo AW109", "AS50" to "Airbus H125", "R44" to "Robinson R44", "B06" to "Bell 206",
        "C130" to "Lockheed C-130 Hercules", "C17" to "Boeing C-17 Globemaster III",
        "K35R" to "Boeing KC-135 Stratotanker", "B74S" to "Boeing 747SP",
        "DC10" to "McDonnell Douglas DC-10",
    )

    /** A readable name for an ICAO type designator, or the code itself when unknown. */
    fun name(code: String): String = names[code.uppercase()] ?: code
}

object Airlines {
    /** IATA two-letter code to ICAO three-letter callsign prefix for common carriers. */
    private val iataToIcao = mapOf(
        "UA" to "UAL", "AA" to "AAL", "DL" to "DAL", "WN" to "SWA", "AS" to "ASA", "B6" to "JBU",
        "NK" to "NKS", "F9" to "FFT", "HA" to "HAL", "G4" to "AAY", "SY" to "SCX", "MX" to "MXY",
        "AC" to "ACA", "WS" to "WJA", "AM" to "AMX", "VB" to "VIV", "Y4" to "VOI", "BA" to "BAW",
        "VS" to "VIR", "LH" to "DLH", "AF" to "AFR", "KL" to "KLM", "IB" to "IBE", "EI" to "EIN",
        "FR" to "RYR", "U2" to "EZY", "LX" to "SWR", "OS" to "AUS", "SK" to "SAS", "AY" to "FIN",
        "TP" to "TAP", "AZ" to "ITY", "TK" to "THY", "LO" to "LOT", "EK" to "UAE", "QR" to "QTR",
        "EY" to "ETD", "SV" to "SVA", "SQ" to "SIA", "CX" to "CPA", "QF" to "QFA", "NZ" to "ANZ",
        "JL" to "JAL", "NH" to "ANA", "KE" to "KAL", "OZ" to "AAR", "CI" to "CAL", "BR" to "EVA",
        "CA" to "CCA", "MU" to "CES", "CZ" to "CSN", "AI" to "AIC", "6E" to "IGO", "ET" to "ETH",
        "SA" to "SAA", "LA" to "LAN", "AV" to "AVA", "CM" to "CMP", "AR" to "ARG", "G3" to "GLO",
        "AD" to "AZU", "FX" to "FDX", "5X" to "UPS", "5Y" to "GTI", "K4" to "CKS",
    )

    /**
     * Converts a flight number as a traveller writes it ("UA 1", "ua1", "AAL123") into the ICAO
     * callsign used by ADS-B ("UAL1", "AAL123").
     */
    fun callsign(flightNumber: String): String {
        val compact = flightNumber.uppercase().filterNot { it.isWhitespace() }
        // IATA codes can contain a digit ("B6", "U2"), so try the two-character code first.
        val number = compact.drop(2)
        val twoLetter = iataToIcao[compact.take(2)]
        if (twoLetter != null && number.isNotEmpty() && number.all { it.isDigit() }) return twoLetter + number
        val firstDigit = compact.indexOfFirst { it.isDigit() }
        if (firstDigit < 0) return compact
        val prefix = compact.substring(0, firstDigit)
        val icao = if (prefix.length == 2) iataToIcao[prefix] else null
        return if (icao != null) icao + compact.substring(firstDigit) else compact
    }
}
