/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database.entities;

// Lightweight aggregate row: one entry per dialog that has saved deleted messages.
// Room maps the SELECT column aliases to these public fields by name.
public class DeletedDialogSummary {
    public long dialogId;
    public int count;
    public int latestMessageId;
    public int latestDate;
}
