#!/bin/bash
set -e

echo "========================================================"
echo "       Samba Sandbox - Initializing Test Data           "
echo "========================================================"

echo ">>> [1/3] Creating test groups..."

create_group_if_not_exists() {
    local g=$1
    if ! getent group "$g" >/dev/null 2>&1; then
        groupadd "$g"
    fi
}

# Create POSIX groups and smb_ groups displayed in the Web UI
for base_group in developers qa hr finance managers interns; do
    create_group_if_not_exists "$base_group"
    create_group_if_not_exists "smb_$base_group"
done

echo ">>> [2/3] Creating test users..."
TEST_PASS=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')

setup_user() {
    local username=$1
    local group=$2
    
    # Create the system account with a non-interactive shell if needed
    if ! id "$username" >/dev/null 2>&1; then
        useradd -m -s /usr/sbin/nologin -g "$group" "$username"
    fi
    
    # Add the account to both POSIX and Web UI groups
    usermod -aG "$group,smb_$group" "$username"
    
    # Set the Samba password
    (echo "$TEST_PASS"; echo "$TEST_PASS") | smbpasswd -s -a "$username" 2>/dev/null || (echo "$TEST_PASS"; echo "$TEST_PASS") | smbpasswd -s "$username"
    smbpasswd -e "$username" 2>/dev/null || true
}

setup_user "dev1" "developers"
setup_user "dev2" "developers"
setup_user "qa1" "qa"
setup_user "qa2" "qa"
setup_user "hr1" "hr"
setup_user "fin1" "finance"
setup_user "boss" "managers"
setup_user "intern1" "interns"

# Add admin to all groups for full access
for g in developers smb_developers qa smb_qa hr smb_hr finance smb_finance managers smb_managers interns smb_interns; do
    usermod -aG "$g" admin 2>/dev/null || true
done

echo ">>> [3/3] Creating directories and generating test files..."
mkdir -p /srv/samba/public
mkdir -p /srv/samba/developers
mkdir -p /srv/samba/qa
mkdir -p /srv/samba/hr
mkdir -p /srv/samba/finance
mkdir -p /srv/samba/management

# Set ownership and groups. SetGID (2) enables group inheritance.
chown root:developers /srv/samba/developers
chmod 2775 /srv/samba/developers

chown root:qa /srv/samba/qa
chmod 2775 /srv/samba/qa

chown root:hr /srv/samba/hr
chmod 2770 /srv/samba/hr

chown root:finance /srv/samba/finance
chmod 2770 /srv/samba/finance

chown root:managers /srv/samba/management
chmod 2770 /srv/samba/management

chown admin:admin /srv/samba/public
chmod 0777 /srv/samba/public

# --- Generate test documents ---

# 1. Public Share
echo "Welcome to Sambryx Public Storage. All users have access here." > /srv/samba/public/welcome_readme.txt
echo "Corporate Policy Guidelines 2026. Confidential and proprietary." > /srv/samba/public/company_policy_2026.pdf
echo "Quarterly All-Hands Presentation slides." > /srv/samba/public/quarterly_presentation.pptx
echo "Department,Shift,Manager\nIT,Day,Alex\nQA,Day,Elena" > /srv/samba/public/general_schedule.xlsx

# 2. Developers Share
echo "public class Main { public static void main(String[] args) { System.out.println(\"Samba Demo\"); } }" > /srv/samba/developers/Main.java
echo "console.log('Samba Developer Node');" > /srv/samba/developers/app.js
echo "# Project Architecture Overview\nMicroservices with Samba File Storage." > /srv/samba/developers/architecture.md
echo "#!/bin/bash\ndocker compose up -d" > /srv/samba/developers/deploy.sh
echo "-- Database Dump Schema 2026\nCREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));" > /srv/samba/developers/database-dump.sql

# 3. QA Share
echo "TestID,Feature,Status\nTC-01,File Download,PASSED\nTC-02,Upload,PASSED\nTC-03,Dark Theme,PASSED" > /srv/samba/qa/test_cases_v1.xlsx
echo "describe('Samba UI Tests', () => { it('loads dashboard', () => {}); });" > /srv/samba/qa/cypress_suite.js
echo "Bug Report #1304: Minor UI alignment glitch in dual-pane file manager." > /srv/samba/qa/bug_report_1304.pdf
echo "Endpoint,p95,p99,ErrorRate\n/api/fs/browse,45ms,120ms,0.00%" > /srv/samba/qa/performance_results.csv

# 4. HR Share
echo "Company Employee Handbook 2026 Edition." > "/srv/samba/hr/Employee Handbook 2026.pdf"
echo "Item,Cost,Approved\nCatering,1500,Yes\nActivities,800,Yes" > "/srv/samba/hr/Q3_Team_Building_Budget.xlsx"
echo "Employment Offer Agreement Template." > "/srv/samba/hr/Offer_Template.docx"
echo "Candidate,Position,Status\nJohn Doe,Senior Java,Interview\nJane Smith,QA Lead,Offer" > "/srv/samba/hr/candidates_list.csv"

# 5. Finance Share
echo "Quarter,Revenue,Expenses,NetProfit\nQ1,540000,320000,220000\nQ2,610000,340000,270000\nQ3,680000,360000,320000" > "/srv/samba/finance/Q3_Revenue_Report.xlsx"
echo "Annual Corporate Tax Declaration 2025." > "/srv/samba/finance/Tax_Declaration_2025.pdf"
echo "Invoice #48992: Cloud Infrastructure Services." > "/srv/samba/finance/Invoice_#48992.pdf"
echo "Year,ProjectedGrowth,CapEx,OpEx\n2027,15%,450000,1200000" > "/srv/samba/finance/Budget_Forecast_2027.xlsx"
echo "Internal Financial Audit Summary and Findings." > "/srv/samba/finance/Audit_Results_Internal.docx"

# 6. Management Share
echo "Executive Board Strategy 2030." > "/srv/samba/management/Company_Strategy_2030.pptx"
echo "Minutes of the Board of Directors Meeting." > "/srv/samba/management/Board_Meeting_Notes.docx"
echo "Strategic Partnership and Non-Disclosure Agreement." > "/srv/samba/management/Partnership_Agreement.pdf"

# Set permissions on the generated files
chmod 0664 /srv/samba/public/*
chown -R admin:admin /srv/samba/public/*

chmod 0664 /srv/samba/developers/*
chown -R root:developers /srv/samba/developers/*

chmod 0664 /srv/samba/qa/*
chown -R root:qa /srv/samba/qa/*

chmod 0660 /srv/samba/hr/*
chown -R root:hr /srv/samba/hr/*

chmod 0660 /srv/samba/finance/*
chown -R root:finance /srv/samba/finance/*

chmod 0660 /srv/samba/management/*
chown -R root:managers /srv/samba/management/*

echo "========================================================"
echo " Test users, groups and files created successfully!"
echo " Groups: developers, qa, hr, finance, managers, interns"
echo " Web UI prefixes: smb_developers, smb_qa, smb_hr, ..."
echo " Mock documents: pdf, docx, xlsx, pptx, csv, java, js"
echo " Users: dev1, dev2, qa1, qa2, hr1, fin1, boss, intern1"
echo " Test accounts use a generated password; reset it explicitly for SMB login tests."
echo "========================================================"
