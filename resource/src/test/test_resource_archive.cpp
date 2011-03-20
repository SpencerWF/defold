#include <stdint.h>
#include <gtest/gtest.h>
#include "../resource_archive.h"

extern char TEST_ARC[];
extern uint32_t TEST_ARC_SIZE;

TEST(dmResourceArchive, Wrap)
{
    dmResourceArchive::HArchive archive = 0;
    dmResourceArchive::Result r = dmResourceArchive::WrapArchiveBuffer((void*) TEST_ARC, TEST_ARC_SIZE, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_OK, r);
    ASSERT_EQ(4U, dmResourceArchive::GetEntryCount(archive));

    const char* names[] = { "archive_data/file4.adc", "archive_data/file1.adc", "archive_data/file3.adc", "archive_data/file2.adc" };
    const char* data[] = { "file4_data", "file1_data", "file3_data", "file2_data" };
    dmResourceArchive::EntryInfo entry_info;
    for (uint32_t i = 0; i < sizeof(names)/sizeof(names[0]); ++i)
    {
        r = dmResourceArchive::FindEntry(archive, names[i], &entry_info);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, r);
        ASSERT_TRUE(strncmp(data[i], (const char*) entry_info.m_Resource, strlen(data[i])) == 0);
    }

    r = dmResourceArchive::FindEntry(archive, "does_not_exists", &entry_info);
    ASSERT_EQ(dmResourceArchive::RESULT_NOT_FOUND, r);
}

int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);
    int ret = RUN_ALL_TESTS();
    return ret;
}
