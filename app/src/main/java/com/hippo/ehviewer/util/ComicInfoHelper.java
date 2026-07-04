/*
 * Copyright 2024 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.util;

import android.util.Xml;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.unifile.UniFile;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Helper class for reading and writing ComicInfo.xml metadata
 * following the ComicInfo v2.0 standard schema.
 */
public final class ComicInfoHelper {

    private static final String COMIC_INFO_FILE = "ComicInfo.xml";
    private static final String NS = null;

    private ComicInfoHelper() {}

    /**
     * Data class holding parsed ComicInfo metadata.
     */
    public static class ComicInfoData {
        public String series;
        public String alternateSeries;
        public List<String> writers;
        public List<String> pencillers;
        public List<String> genres;
        public String web;
        public int pageCount;
        public String languageISO;
        public List<String> characters;
        public List<String> teams;
        public float communityRating;

        public ComicInfoData() {
            pageCount = 0;
            communityRating = -1.0f;
        }
    }

    /**
     * Write ComicInfo.xml to a download directory.
     */
    public static boolean writeComicInfo(UniFile dir, GalleryInfo info) {
        if (dir == null || info == null) return false;

        UniFile comicInfoFile = dir.findFile(COMIC_INFO_FILE);
        if (comicInfoFile == null) {
            comicInfoFile = dir.createFile(COMIC_INFO_FILE);
        }
        if (comicInfoFile == null) return false;

        try (OutputStream os = comicInfoFile.openOutputStream()) {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(os, "UTF-8");
            serializer.startDocument("UTF-8", true);
            serializer.startTag(NS, "ComicInfo");

            // XML Schema attributes
            serializer.attribute(NS, "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            serializer.attribute(NS, "xsi:noNamespaceSchemaLocation",
                    "https://raw.githubusercontent.com/anansi-project/comicinfo/main/schema/v2.0/ComicInfo.xsd");

            writeComicInfoBody(serializer, info);

            serializer.endTag(NS, "ComicInfo");
            serializer.endDocument();
            serializer.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Read ComicInfo.xml from a CBZ/ZIP archive file.
     */
    @Nullable
    public static ComicInfoData readComicInfoFromCbz(UniFile cbzFile) {
        if (cbzFile == null) return null;

        try (InputStream is = cbzFile.openInputStream()) {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (COMIC_INFO_FILE.equals(entry.getName())) {
                    return parseComicInfoXml(zis);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Check if a CBZ file contains ComicInfo.xml.
     */
    public static boolean hasComicInfo(UniFile cbzFile) {
        if (cbzFile == null) return false;
        try (InputStream is = cbzFile.openInputStream()) {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (COMIC_INFO_FILE.equals(entry.getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Convert ComicInfoData to a GalleryInfo for import/restore purposes.
     */
    public static GalleryInfo comicInfoToGalleryInfo(ComicInfoData data, long gid, String token) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.token = token != null ? token : "";
        info.title = data.series;
        info.titleJpn = data.alternateSeries;
        info.pages = data.pageCount;
        info.rating = data.communityRating;
        info.simpleLanguage = data.languageISO != null ? data.languageISO.toUpperCase(Locale.US) : null;

        // Build simpleTags from ComicInfo data
        List<String> tags = new ArrayList<>();
        if (data.writers != null) {
            for (String w : data.writers) tags.add("artist:" + w);
        }
        if (data.characters != null) {
            for (String c : data.characters) tags.add("character:" + c);
        }
        if (data.teams != null) {
            for (String t : data.teams) tags.add("parody:" + t);
        }
        if (data.genres != null) {
            for (String g : data.genres) tags.add(g);
        }
        info.simpleTags = tags.toArray(new String[0]);

        return info;
    }

    /**
     * Write or update ComicInfo.xml inside an existing CBZ/ZIP file.
     * Since ZIP entries cannot be updated in place, this rewrites the entire archive via a temp file.
     * @return true on success
     */
    public static boolean updateComicInfoInCbz(UniFile cbzFile, GalleryInfo info) {
        if (cbzFile == null || info == null) return false;
        UniFile parent = cbzFile.getParent();
        if (parent == null) return false;

        UniFile tempFile = parent.createFile(cbzFile.getName() + ".tmp");
        if (tempFile == null) return false;

        try {
            boolean success = rebuildCbzWithComicInfo(cbzFile, tempFile, info);
            if (success) {
                cbzFile.delete();
                tempFile.renameTo(cbzFile.getName());
            } else {
                tempFile.delete();
            }
            return success;
        } catch (Exception e) {
            tempFile.delete();
            e.printStackTrace();
            return false;
        }
    }

    private static boolean rebuildCbzWithComicInfo(UniFile src, UniFile dst, GalleryInfo info) throws IOException {
        try (OutputStream dstOs = dst.openOutputStream();
             ZipOutputStream zos = new ZipOutputStream(dstOs);
             InputStream srcIs = src.openInputStream();
             ZipInputStream zis = new ZipInputStream(srcIs)) {

            // Copy existing entries except old ComicInfo.xml
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (COMIC_INFO_FILE.equals(entry.getName())) {
                    continue; // Will be replaced
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }

            // Write new ComicInfo.xml
            zos.putNextEntry(new ZipEntry(COMIC_INFO_FILE));
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(zos, "UTF-8");
            serializer.startDocument("UTF-8", true);
            serializer.startTag(NS, "ComicInfo");
            serializer.attribute(NS, "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            serializer.attribute(NS, "xsi:noNamespaceSchemaLocation",
                    "https://raw.githubusercontent.com/anansi-project/comicinfo/main/schema/v2.0/ComicInfo.xsd");
            writeComicInfoBody(serializer, info);
            serializer.endTag(NS, "ComicInfo");
            serializer.endDocument();
            serializer.flush();
            zos.closeEntry();
        }
        return true;
    }

    private static void writeComicInfoBody(XmlSerializer serializer, GalleryInfo info) throws IOException {
        writeTag(serializer, "Series", info.title);
        writeTag(serializer, "AlternateSeries", info.titleJpn);

        List<String> artists = new ArrayList<>();
        List<String> characters = new ArrayList<>();
        List<String> parodies = new ArrayList<>();
        List<String> otherTags = new ArrayList<>();
        extractTagsByNamespace(info.simpleTags, artists, characters, parodies, otherTags);

        writeTagList(serializer, "Writer", artists);
        writeTagList(serializer, "Penciller", artists);
        writeTagList(serializer, "Genre", otherTags);

        writeTag(serializer, "Web", EhUrl.getGalleryDetailUrl(info.gid, info.token));
        writeIntTag(serializer, "PageCount", info.pages);

        if (info.simpleLanguage != null) {
            writeTag(serializer, "LanguageISO", info.simpleLanguage.toLowerCase(Locale.US));
        }

        writeTagList(serializer, "Characters", characters);
        writeTagList(serializer, "Teams", parodies);

        if (info.rating >= 0) {
            writeTag(serializer, "CommunityRating", String.format(Locale.US, "%.1f", info.rating));
        }
    }

    /**
     * Extract tags from simpleTags array into categorized lists.
     */
    public static void extractTagsByNamespace(String[] simpleTags,
                                               List<String> artists,
                                               List<String> characters,
                                               List<String> parodies,
                                               List<String> otherTags) {
        if (simpleTags == null) return;
        for (String tag : simpleTags) {
            if (tag == null) continue;
            int colon = tag.indexOf(':');
            if (colon > 0) {
                String ns = tag.substring(0, colon).toLowerCase(Locale.US);
                String value = tag.substring(colon + 1);
                switch (ns) {
                    case "artist":
                    case "cosplayer":
                        if (artists != null) artists.add(value);
                        break;
                    case "character":
                        if (characters != null) characters.add(value);
                        break;
                    case "parody":
                        if (parodies != null) parodies.add(value);
                        break;
                    default:
                        if (otherTags != null) otherTags.add(tag);
                        break;
                }
            } else {
                if (otherTags != null) otherTags.add(tag);
            }
        }
    }

    // --- Private helpers ---

    @Nullable
    private static ComicInfoData parseComicInfoXml(InputStream is) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "UTF-8");
            parser.nextTag();

            ComicInfoData data = new ComicInfoData();

            parser.require(XmlPullParser.START_TAG, NS, "ComicInfo");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                String tagName = parser.getName();
                switch (tagName) {
                    case "Series":
                        data.series = readText(parser);
                        break;
                    case "AlternateSeries":
                        data.alternateSeries = readText(parser);
                        break;
                    case "Writer":
                        data.writers = readTextList(parser);
                        break;
                    case "Penciller":
                        data.pencillers = readTextList(parser);
                        break;
                    case "Genre":
                        data.genres = readTextList(parser);
                        break;
                    case "Web":
                        data.web = readText(parser);
                        break;
                    case "PageCount":
                        data.pageCount = Integer.parseInt(readText(parser));
                        break;
                    case "LanguageISO":
                        data.languageISO = readText(parser);
                        break;
                    case "Characters":
                        data.characters = readTextList(parser);
                        break;
                    case "Teams":
                        data.teams = readTextList(parser);
                        break;
                    case "CommunityRating":
                        try {
                            data.communityRating = Float.parseFloat(readText(parser));
                        } catch (NumberFormatException ignored) {}
                        break;
                    default:
                        skip(parser);
                        break;
                }
            }
            return data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String readText(XmlPullParser parser) throws Exception {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result != null ? result : "";
    }

    private static List<String> readTextList(XmlPullParser parser) throws Exception {
        List<String> list = new ArrayList<>();
        // ComicInfo tags are typically single text values, but some apps
        // use comma-separated lists or multiple elements
        String text = readText(parser);
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    private static void skip(XmlPullParser parser) throws Exception {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
            }
        }
    }

    private static void writeTag(XmlSerializer serializer, String name, String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        serializer.startTag(NS, name);
        serializer.text(value);
        serializer.endTag(NS, name);
    }

    private static void writeIntTag(XmlSerializer serializer, String name, int value) throws IOException {
        serializer.startTag(NS, name);
        serializer.text(String.valueOf(value));
        serializer.endTag(NS, name);
    }

    private static void writeTagList(XmlSerializer serializer, String name, List<String> values) throws IOException {
        if (values == null || values.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        serializer.startTag(NS, name);
        serializer.text(sb.toString());
        serializer.endTag(NS, name);
    }
}
