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
make "JFLAGS=--release 11 -encoding UTF-8" repro_jar

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
* Tue Nov 21 2023 Enno Ruijters <mail@ennoruijters.nl> - 1.2.0-1
- Various bugfixes discovered preparing for QComp 2023.
- Add support for two-sided Until formulae.
- Support for more Jani features used in the QComp 2023 models.
* Fri Oct 07 2022 Enno Ruijters <mail@ennoruijters.nl> - 1.1.0-1
- Performance improvements.
- Support for Storm as DFT front-end.
- Support for more Jani features.
* Tue May 05 2020 Enno Ruijters <mail@ennoruijters.nl> - 1.0.2-1
- Initial RPM relase
