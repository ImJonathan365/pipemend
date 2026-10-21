package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

class RawRecordReaderTest {

    @TempDir
    Path dir;

    private int files;

    @Test
    void readsRecordsKeyedByHeaderInFileOrder() throws Exception {
        List<RawRecord> records = readAll("a,b,c\n1,2,3\n4,5,6\n");

        assertThat(records)
                .containsExactly(
                        new RawRecord.Parsed(1, Map.of("a", "1", "b", "2", "c", "3")),
                        new RawRecord.Parsed(2, Map.of("a", "4", "b", "5", "c", "6")));
        assertThat(((RawRecord.Parsed) records.getFirst()).valuesByHeader().keySet())
                .containsExactly("a", "b", "c");
    }

    @Test
    void ac_01_5_aWrongColumnCountIsMalformedVerbatimAndReadingGoesOn() throws Exception {
        List<RawRecord> records = readAll("a,b,c\n1,2,3,4\n5,6\n7,8,9\n");

        assertThat(records)
                .containsExactly(
                        new RawRecord.Malformed(1, "1,2,3,4", 3, 4),
                        new RawRecord.Malformed(2, "5,6", 3, 2),
                        new RawRecord.Parsed(3, Map.of("a", "7", "b", "8", "c", "9")));
    }

    @Test
    void ac_01_5_anEmptyLineIsAMalformedRecord() throws Exception {
        assertThat(readAll("a,b\n1,2\n\n3,4\n")).element(1).isEqualTo(new RawRecord.Malformed(2, "", 2, 0));
    }

    @Test
    void ac_01_7_aLeadingBomIsRemoved() throws Exception {
        RawRecordReader reader = open(bytes("﻿Invoice,Quantity\n536365,6\n"));

        assertThat(reader.headers()).containsExactly("Invoice", "Quantity");
    }

    @Test
    void ac_01_7_crlfAndLfLineEndingsAreBothAccepted() throws Exception {
        assertThat(readAll("a,b\r\n1,2\r\n3,4\n"))
                .containsExactly(
                        new RawRecord.Parsed(1, Map.of("a", "1", "b", "2")),
                        new RawRecord.Parsed(2, Map.of("a", "3", "b", "4")));
    }

    @Test
    void ac_01_7_quotedFieldsKeepCommasQuotesAndLineBreaksAndRowsCountLogicalRecords() throws Exception {
        List<RawRecord> records = readAll("a,b\n\"x, y\",\"say \"\"hi\"\"\"\n\"line 1\r\nline 2\",z\n3,4\n");

        assertThat(records)
                .containsExactly(
                        new RawRecord.Parsed(1, Map.of("a", "x, y", "b", "say \"hi\"")),
                        new RawRecord.Parsed(2, Map.of("a", "line 1\nline 2", "b", "z")),
                        new RawRecord.Parsed(3, Map.of("a", "3", "b", "4")));
    }

    @Test
    void valuesAreNeverTrimmed() throws Exception {
        assertThat(readAll("a,b\n 85123A ,\t6\n"))
                .containsExactly(new RawRecord.Parsed(1, Map.of("a", " 85123A ", "b", "\t6")));
    }

    @Test
    void aLineStartingWithHashIsDataAndATrailingBackslashJoinsNothing() throws Exception {
        assertThat(readAll("a,b\n#1,x\\\n2,y\n"))
                .containsExactly(
                        new RawRecord.Parsed(1, Map.of("a", "#1", "b", "x\\")),
                        new RawRecord.Parsed(2, Map.of("a", "2", "b", "y")));
    }

    @Test
    void aHeaderOnlyFileHasNoRecords() throws Exception {
        assertThat(readAll("a,b\n")).isEmpty();
    }

    @Test
    void ac_01_7_invalidUtf8InARecordIsAnInvalidFileFormat() {
        // Decoding runs ahead in buffer-sized blocks, so the error may surface on open or on a later read.
        byte[] file = concat(bytes("a,b\n" + "1,2\n".repeat(5000)), new byte[] {(byte) 0xC3, 0x28, ',', '3', '\n'});

        assertThatThrownBy(() -> readAll(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void ac_01_7_invalidUtf8InTheHeaderIsAnInvalidFileFormat() throws Exception {
        assertThatThrownBy(() -> open(new byte[] {'a', ',', (byte) 0xFF, '\n'}))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void anEmptyFileIsAnInvalidFileFormat() {
        assertThatThrownBy(() -> open(new byte[0]))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessageContaining("no header");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a,,c\n", "a, ,c\n", "a,\"\t\",c\n", "a,b,a\n"})
    void ac_01_8_anEmptyOrRepeatedHeaderIsAnInvalidFileFormat(String header) {
        assertThatThrownBy(() -> open(bytes(header + "1,2,3\n"))).isInstanceOf(InvalidFileFormatException.class);
    }

    @Test
    void anUnclosedQuoteIsAnInvalidFileFormat() throws Exception {
        RawRecordReader reader = open(bytes("a,b\n1,2\n\"3,4\n5,6\n"));

        assertThat(reader.read()).isNotNull();
        assertThatThrownBy(reader::read).isInstanceOf(InvalidFileFormatException.class);
    }

    // Pins current behaviour, not desired behaviour: a stray quote inside an unquoted field opens a quoted section
    // that only the next quote closes, wherever it is.
    @Test
    void aSingleStrayQuoteInAnUnquotedFieldRejectsTheFile() throws Exception {
        RawRecordReader reader = open(bytes("a,b\n1,5\" TILE\n2,x\n3,y\n"));

        assertThatThrownBy(reader::read).isInstanceOf(InvalidFileFormatException.class);
    }

    @Test
    void twoStrayQuotesSwallowTheRowsBetweenThemIntoOneParsedRecord() throws Exception {
        List<RawRecord> records = readAll("a,b\n1,5\" TILE\n2,x\n3,8\" PLATE\n4,z\n");

        assertThat(records)
                .containsExactly(
                        new RawRecord.Parsed(1, Map.of("a", "1", "b", "5\" TILE\n2,x\n3,8\" PLATE")),
                        new RawRecord.Parsed(2, Map.of("a", "4", "b", "z")));
    }

    @Test
    void nfr_04_aReopenedReaderResumesAfterTheLastSavedRecord() throws Exception {
        byte[] file = bytes("a,b\n1,x\n2,\"multi\nline\"\n3,z\n4,w\n");
        ExecutionContext context = new ExecutionContext();
        RawRecordReader first = open(file, context);
        first.read();
        first.read();
        first.update(context);
        first.read();
        first.close();

        RawRecordReader restarted = open(file, context);

        assertThat(restarted.read()).isEqualTo(new RawRecord.Parsed(3, Map.of("a", "3", "b", "z")));
        assertThat(restarted.read()).isEqualTo(new RawRecord.Parsed(4, Map.of("a", "4", "b", "w")));
        assertThat(restarted.read()).isNull();
    }

    private List<RawRecord> readAll(String content) throws Exception {
        return readAll(bytes(content));
    }

    private List<RawRecord> readAll(byte[] content) throws Exception {
        RawRecordReader reader = open(content);
        List<RawRecord> records = new ArrayList<>();
        for (RawRecord record = reader.read(); record != null; record = reader.read()) {
            records.add(record);
        }
        reader.close();
        return records;
    }

    private RawRecordReader open(byte[] content) throws IOException {
        return open(content, new ExecutionContext());
    }

    private RawRecordReader open(byte[] content, ExecutionContext context) throws IOException {
        Path file = dir.resolve("source-" + files++ + ".csv");
        Files.write(file, content);
        RawRecordReader reader = new RawRecordReader(new FileSystemResource(file));
        reader.open(context);
        return reader;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] all = new byte[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
