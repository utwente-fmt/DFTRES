Name:		dftres
Version:	<version>
Release:	1
Summary:	The Dynamic Fault Tree Rare Event Simulator

License:	GPLv3+
URL:		https://github.com/utwente-fmt/DFTRES
Source0:	%{name}-%{version}.tar.xz
BuildArch:      noarch

BuildRequires:	make, java-latest-openjdk-devel >= 1:11, git, bc
Requires:	java-latest-openjdk >= 1:11

%description
The Dynamic Fault Tree Rare Event Simulator

%prep
%setup

%build
make "JFLAGS=-target 11 -source 11 -encoding UTF-8" repro_jar

%check
cd tests && bash test.sh

%install
mkdir -p %{buildroot}/%{_datadir}/dftres
mkdir -p %{buildroot}/%{_bindir}
install -m 0755 DFTRES.jar %{buildroot}/%{_datadir}/dftres
install -m 0755 package/DFTRES %{buildroot}/%{_bindir}

%files
%{_bindir}/DFTRES
%{_datadir}/dftres/DFTRES.jar

%changelog
* Tue May 05 2020 Enno Ruijters <mail@ennoruijters.nl> - 1.0.2-1
- Initial RPM relase
