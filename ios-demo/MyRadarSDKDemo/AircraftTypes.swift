//
//  AircraftTypes.swift
//  MyRadarSDKDemo
//
//  Small lookup tables: ICAO aircraft type codes to readable names, and IATA
//  airline codes to the ICAO prefixes used in ADS-B callsigns.
//

import Foundation

enum AircraftTypes {
    private static let names: [String: String] = [
        "A19N": "Airbus A319neo", "A20N": "Airbus A320neo", "A21N": "Airbus A321neo",
        "A318": "Airbus A318", "A319": "Airbus A319", "A320": "Airbus A320", "A321": "Airbus A321",
        "A332": "Airbus A330-200", "A333": "Airbus A330-300", "A338": "Airbus A330-800neo",
        "A339": "Airbus A330-900neo", "A342": "Airbus A340-200", "A343": "Airbus A340-300",
        "A346": "Airbus A340-600", "A359": "Airbus A350-900", "A35K": "Airbus A350-1000",
        "A388": "Airbus A380-800", "A306": "Airbus A300-600", "A310": "Airbus A310",
        "B37M": "Boeing 737 MAX 7", "B38M": "Boeing 737 MAX 8", "B39M": "Boeing 737 MAX 9",
        "B3XM": "Boeing 737 MAX 10", "B712": "Boeing 717-200", "B733": "Boeing 737-300",
        "B734": "Boeing 737-400", "B735": "Boeing 737-500", "B736": "Boeing 737-600",
        "B737": "Boeing 737-700", "B738": "Boeing 737-800", "B739": "Boeing 737-900",
        "B744": "Boeing 747-400", "B748": "Boeing 747-8", "B752": "Boeing 757-200",
        "B753": "Boeing 757-300", "B762": "Boeing 767-200", "B763": "Boeing 767-300",
        "B764": "Boeing 767-400", "B772": "Boeing 777-200", "B77L": "Boeing 777-200LR",
        "B773": "Boeing 777-300", "B77W": "Boeing 777-300ER", "B778": "Boeing 777-8",
        "B779": "Boeing 777-9", "B788": "Boeing 787-8 Dreamliner", "B789": "Boeing 787-9 Dreamliner",
        "B78X": "Boeing 787-10 Dreamliner", "MD11": "McDonnell Douglas MD-11",
        "MD88": "McDonnell Douglas MD-88", "MD90": "McDonnell Douglas MD-90",
        "E170": "Embraer E170", "E175": "Embraer E175", "E190": "Embraer E190", "E195": "Embraer E195",
        "E290": "Embraer E190-E2", "E295": "Embraer E195-E2", "E45X": "Embraer ERJ-145",
        "E135": "Embraer ERJ-135", "E145": "Embraer ERJ-145",
        "CRJ2": "Bombardier CRJ-200", "CRJ7": "Bombardier CRJ-700", "CRJ9": "Bombardier CRJ-900",
        "CRJX": "Bombardier CRJ-1000", "BCS1": "Airbus A220-100", "BCS3": "Airbus A220-300",
        "DH8A": "De Havilland Dash 8-100", "DH8B": "De Havilland Dash 8-200",
        "DH8C": "De Havilland Dash 8-300", "DH8D": "De Havilland Dash 8-400",
        "AT72": "ATR 72", "AT75": "ATR 72-500", "AT76": "ATR 72-600", "AT45": "ATR 42-500",
        "SF34": "Saab 340", "C208": "Cessna 208 Caravan", "PC12": "Pilatus PC-12",
        "C172": "Cessna 172", "C182": "Cessna 182", "SR22": "Cirrus SR22", "BE20": "Beechcraft King Air 200",
        "B350": "Beechcraft King Air 350", "GLF4": "Gulfstream IV", "GLF5": "Gulfstream V",
        "GLF6": "Gulfstream G650", "G280": "Gulfstream G280", "CL35": "Bombardier Challenger 350",
        "CL60": "Bombardier Challenger 600", "GL7T": "Bombardier Global 7500",
        "C56X": "Cessna Citation Excel", "C68A": "Cessna Citation Latitude", "C25A": "Cessna CitationJet CJ2",
        "C25B": "Cessna CitationJet CJ3", "C750": "Cessna Citation X", "E55P": "Embraer Phenom 300",
        "E50P": "Embraer Phenom 100", "LJ45": "Learjet 45", "LJ60": "Learjet 60",
        "F2TH": "Dassault Falcon 2000", "FA7X": "Dassault Falcon 7X", "FA8X": "Dassault Falcon 8X",
        "H60": "Sikorsky S-70 Black Hawk", "EC35": "Airbus H135", "EC45": "Airbus H145",
        "A109": "Leonardo AW109", "AS50": "Airbus H125", "R44": "Robinson R44", "B06": "Bell 206",
        "C130": "Lockheed C-130 Hercules", "C17": "Boeing C-17 Globemaster III",
        "K35R": "Boeing KC-135 Stratotanker", "B74S": "Boeing 747SP", "DC10": "McDonnell Douglas DC-10",
    ]

    /// A readable name for an ICAO type designator, or the code itself when unknown.
    static func name(for code: String) -> String {
        names[code.uppercased()] ?? code
    }
}

enum Airlines {
    /// IATA two-letter code to ICAO three-letter callsign prefix for common carriers.
    private static let iataToICAO: [String: String] = [
        "UA": "UAL", "AA": "AAL", "DL": "DAL", "WN": "SWA", "AS": "ASA", "B6": "JBU",
        "NK": "NKS", "F9": "FFT", "HA": "HAL", "G4": "AAY", "SY": "SCX", "MX": "MXY",
        "AC": "ACA", "WS": "WJA", "AM": "AMX", "VB": "VIV", "Y4": "VOI",
        "BA": "BAW", "VS": "VIR", "LH": "DLH", "AF": "AFR", "KL": "KLM", "IB": "IBE",
        "EI": "EIN", "FR": "RYR", "U2": "EZY", "LX": "SWR", "OS": "AUS", "SK": "SAS",
        "AY": "FIN", "TP": "TAP", "AZ": "ITY", "TK": "THY", "LO": "LOT",
        "EK": "UAE", "QR": "QTR", "EY": "ETD", "SV": "SVA",
        "SQ": "SIA", "CX": "CPA", "QF": "QFA", "NZ": "ANZ", "JL": "JAL", "NH": "ANA",
        "KE": "KAL", "OZ": "AAR", "CI": "CAL", "BR": "EVA", "CA": "CCA", "MU": "CES",
        "CZ": "CSN", "AI": "AIC", "6E": "IGO", "ET": "ETH", "SA": "SAA", "LA": "LAN",
        "AV": "AVA", "CM": "CMP", "AR": "ARG", "G3": "GLO", "AD": "AZU",
        "FX": "FDX", "5X": "UPS", "5Y": "GTI", "K4": "CKS",
    ]

    /// Converts a flight number as a traveller writes it ("UA 1", "ua1", "AAL123")
    /// into the ICAO callsign used by ADS-B ("UAL1", "AAL123").
    static func callsign(fromFlightNumber input: String) -> String {
        let compact = input.uppercased().filter { !$0.isWhitespace }
        guard let firstDigit = compact.firstIndex(where: \.isNumber) else {
            return compact
        }
        let prefix = String(compact[..<firstDigit])
        let number = String(compact[firstDigit...])
        if prefix.count == 2, let icao = iataToICAO[prefix] {
            return icao + number
        }
        return compact
    }
}
